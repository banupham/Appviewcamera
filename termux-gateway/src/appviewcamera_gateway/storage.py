from __future__ import annotations

import configparser
import json
import os
import re
import subprocess
import tempfile
import threading
from pathlib import Path
from typing import Any, Callable

from .config import _atomic_json, _read_json


REMOTE_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")
Runner = Callable[..., subprocess.CompletedProcess[str]]


class GoogleDriveStore:
    """Owns rclone Drive credentials without exposing them through the API."""

    def __init__(self, home: Path, runner: Runner = subprocess.run):
        self.metadata_path = home / "config" / "google-drives.json"
        self.rclone_config_path = home / "config" / "rclone.conf"
        self.runner = runner
        self._lock = threading.RLock()

    def list(self) -> list[dict[str, Any]]:
        with self._lock:
            raw = self._read_metadata()
            configured = set(self._drive_remote_ids())
            return [
                self._public_account(account, configured)
                for account in raw["accounts"]
                if isinstance(account, dict)
            ]

    def add(self, remote_id: str, display_name: str, oauth_token: str) -> dict[str, Any]:
        normalized_id = remote_id.lower().strip()
        if not REMOTE_ID_PATTERN.fullmatch(normalized_id):
            raise ValueError("id Drive chỉ gồm a-z, 0-9, _ hoặc -, tối đa 32 ký tự")
        token = self._validate_token(oauth_token)
        with self._lock:
            parser = self._read_rclone_config()
            if parser.has_section(normalized_id):
                parser.remove_section(normalized_id)
            parser.add_section(normalized_id)
            parser.set(normalized_id, "type", "drive")
            parser.set(normalized_id, "scope", "drive.file")
            parser.set(normalized_id, "token", json.dumps(token, ensure_ascii=False, separators=(",", ":")))
            self._write_rclone_config(parser)

            raw = self._read_metadata()
            accounts = [item for item in raw["accounts"] if item.get("id") != normalized_id]
            accounts.append(
                {
                    "id": normalized_id,
                    "display_name": display_name.strip() or normalized_id,
                    "active": not accounts,
                    "status": "NOT_CHECKED",
                    "last_error": None,
                    "quota": None,
                }
            )
            raw["accounts"] = accounts
            _atomic_json(self.metadata_path, raw)
            return self._public_account(accounts[-1], {normalized_id})

    def delete(self, remote_id: str) -> bool:
        normalized_id = remote_id.lower().strip()
        with self._lock:
            raw = self._read_metadata()
            existing = next((item for item in raw["accounts"] if item.get("id") == normalized_id), None)
            parser = self._read_rclone_config()
            configured = parser.has_section(normalized_id)
            if existing is None and not configured:
                return False
            if configured:
                parser.remove_section(normalized_id)
                self._write_rclone_config(parser)
            accounts = [item for item in raw["accounts"] if item.get("id") != normalized_id]
            if accounts and not any(bool(item.get("active")) for item in accounts):
                accounts[0]["active"] = True
            raw["accounts"] = accounts
            _atomic_json(self.metadata_path, raw)
            return True

    def refresh(self, remote_id: str) -> dict[str, Any]:
        normalized_id = remote_id.lower().strip()
        with self._lock:
            raw = self._read_metadata()
            account = next((item for item in raw["accounts"] if item.get("id") == normalized_id), None)
            if account is None or normalized_id not in self._drive_remote_ids():
                raise ValueError("Không tìm thấy tài khoản Google Drive")
            try:
                completed = self.runner(
                    [
                        "rclone",
                        "--config",
                        str(self.rclone_config_path),
                        "about",
                        f"{normalized_id}:",
                        "--json",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=45,
                    check=False,
                )
                if completed.returncode != 0:
                    raise RuntimeError((completed.stderr or completed.stdout or "rclone thất bại").strip()[-500:])
                quota = json.loads(completed.stdout or "{}")
                account["status"] = "ONLINE"
                account["last_error"] = None
                account["quota"] = {
                    key: int(quota[key])
                    for key in ("total", "used", "free", "trashed", "other")
                    if quota.get(key) is not None
                }
            except (OSError, subprocess.TimeoutExpired, RuntimeError, json.JSONDecodeError) as error:
                account["status"] = "ERROR"
                account["last_error"] = str(error)
                account["quota"] = None
            _atomic_json(self.metadata_path, raw)
            return self._public_account(account, {normalized_id})

    def active_account(self) -> dict[str, Any] | None:
        accounts = self.list()
        return next(
            (account for account in accounts if account["active"] and account["configured"]),
            next((account for account in accounts if account["configured"]), None),
        )

    def upload_account(self) -> dict[str, Any] | None:
        accounts = [account for account in self.list() if account["configured"]]
        if not accounts:
            return None
        active = next((account for account in accounts if account["active"]), accounts[0])
        policy = self._read_metadata().get("policy", {})
        threshold = max(1, min(100, int(policy.get("switch_at_percent", 90))))
        quota = active.get("quota") or {}
        total = int(quota.get("total") or 0)
        used = int(quota.get("used") or 0)
        should_switch = active.get("status") == "ERROR" or (
            total > 0 and used * 100 >= total * threshold
        )
        if should_switch:
            alternative = next(
                (
                    account for account in accounts
                    if account["id"] != active["id"] and account["status"] != "ERROR"
                ),
                None,
            )
            if alternative:
                return self.activate(str(alternative["id"]))
        return active

    def activate(self, remote_id: str) -> dict[str, Any]:
        normalized_id = self._require_remote(remote_id)
        with self._lock:
            raw = self._read_metadata()
            selected = None
            for account in raw["accounts"]:
                account["active"] = account.get("id") == normalized_id
                if account["active"]:
                    selected = account
            if selected is None:
                raise ValueError("Không tìm thấy tài khoản Google Drive")
            _atomic_json(self.metadata_path, raw)
            return self._public_account(selected, {normalized_id})

    def retry_seconds(self) -> list[int]:
        policy = self._read_metadata().get("policy", {})
        raw = policy.get("retry_seconds", [60, 300, 900, 3600])
        values = [max(10, int(value)) for value in raw if isinstance(value, (int, float))]
        return values or [60, 300, 900, 3600]

    def remote_root(self) -> str:
        policy = self._read_metadata().get("policy", {})
        return self._safe_remote_path(str(policy.get("remote_root", "CameraBackup")))

    def upload_file(self, remote_id: str, local_path: Path, remote_path: str) -> None:
        normalized_id = self._require_remote(remote_id)
        safe_path = self._safe_remote_path(remote_path)
        completed = self._run_rclone(
            "copyto", str(local_path), f"{normalized_id}:{safe_path}",
            "--transfers", "1", "--checkers", "2", "--retries", "1",
            "--low-level-retries", "2", timeout=600,
        )
        if completed.returncode != 0:
            raise RuntimeError(self._rclone_error(completed))
        stat = self.remote_stat(normalized_id, safe_path)
        if int(stat.get("Size", -1)) != local_path.stat().st_size:
            raise RuntimeError("Kích thước file trên Google Drive không khớp bản cục bộ")

    def download_file(self, remote_id: str, remote_path: str, local_path: Path) -> None:
        normalized_id = self._require_remote(remote_id)
        safe_path = self._safe_remote_path(remote_path)
        local_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = local_path.with_suffix(local_path.suffix + ".part")
        try:
            completed = self._run_rclone(
                "copyto", f"{normalized_id}:{safe_path}", str(temporary),
                "--transfers", "1", "--checkers", "2", "--retries", "2",
                "--low-level-retries", "3", timeout=600,
            )
            if completed.returncode != 0:
                raise RuntimeError(self._rclone_error(completed))
            remote_size = int(self.remote_stat(normalized_id, safe_path).get("Size", -1))
            if not temporary.is_file() or temporary.stat().st_size != remote_size:
                raise RuntimeError("File tải từ Google Drive chưa đầy đủ")
            temporary.replace(local_path)
        finally:
            temporary.unlink(missing_ok=True)

    def remote_stat(self, remote_id: str, remote_path: str) -> dict[str, Any]:
        normalized_id = self._require_remote(remote_id)
        safe_path = self._safe_remote_path(remote_path)
        completed = self._run_rclone(
            "lsjson", f"{normalized_id}:{safe_path}", "--stat", "--no-mimetype",
            "--no-modtime", timeout=90,
        )
        if completed.returncode != 0:
            raise RuntimeError(self._rclone_error(completed))
        try:
            value = json.loads(completed.stdout or "{}")
        except json.JSONDecodeError as error:
            raise RuntimeError("rclone trả về thông tin file không hợp lệ") from error
        if not isinstance(value, dict) or value.get("IsDir") is True:
            raise RuntimeError("Không tìm thấy file trên Google Drive")
        return value

    def _run_rclone(self, *arguments: str, timeout: int) -> subprocess.CompletedProcess[str]:
        return self.runner(
            ["rclone", "--config", str(self.rclone_config_path), *arguments],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )

    def _require_remote(self, remote_id: str) -> str:
        normalized_id = remote_id.lower().strip()
        if not REMOTE_ID_PATTERN.fullmatch(normalized_id):
            raise ValueError("ID Google Drive không hợp lệ")
        if normalized_id not in self._drive_remote_ids():
            raise ValueError("Tài khoản Google Drive chưa được cấu hình")
        return normalized_id

    @staticmethod
    def _safe_remote_path(remote_path: str) -> str:
        normalized = remote_path.replace("\\", "/").strip("/")
        parts = normalized.split("/")
        if not normalized or any(part in ("", ".", "..") for part in parts):
            raise ValueError("Đường dẫn Google Drive không hợp lệ")
        return "/".join(parts)

    @staticmethod
    def _rclone_error(completed: subprocess.CompletedProcess[str]) -> str:
        return (completed.stderr or completed.stdout or "rclone thất bại").strip()[-500:]

    def _read_metadata(self) -> dict[str, Any]:
        raw = _read_json(
            self.metadata_path,
            {
                "accounts": [],
                "policy": {
                    "switch_at_percent": 90,
                    "quota_refresh_hours": 6,
                    "retry_seconds": [60, 300, 900, 3600],
                    "remote_root": "CameraBackup",
                },
            },
        )
        if not isinstance(raw, dict):
            raise ValueError("google-drives.json không hợp lệ")
        raw.setdefault("accounts", [])
        raw.setdefault("policy", {})
        return raw

    def _read_rclone_config(self) -> configparser.ConfigParser:
        parser = configparser.ConfigParser(interpolation=None)
        if self.rclone_config_path.exists():
            parser.read(self.rclone_config_path, encoding="utf-8")
        return parser

    def _write_rclone_config(self, parser: configparser.ConfigParser) -> None:
        self.rclone_config_path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".rclone.conf.", dir=self.rclone_config_path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                parser.write(handle)
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, self.rclone_config_path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def _drive_remote_ids(self) -> list[str]:
        parser = self._read_rclone_config()
        return [section for section in parser.sections() if parser.get(section, "type", fallback="") == "drive"]

    @staticmethod
    def _validate_token(raw_token: str) -> dict[str, Any]:
        if not raw_token or len(raw_token) > 16_384:
            raise ValueError("OAuth token Google Drive không hợp lệ")
        try:
            token = json.loads(raw_token)
        except json.JSONDecodeError as error:
            raise ValueError("OAuth token phải là JSON do rclone authorize tạo") from error
        if not isinstance(token, dict) or not token.get("access_token") or not token.get("token_type"):
            raise ValueError("OAuth token thiếu access_token hoặc token_type")
        return token

    @staticmethod
    def _public_account(account: dict[str, Any], configured: set[str]) -> dict[str, Any]:
        account_id = str(account.get("id", ""))
        return {
            "id": account_id,
            "display_name": str(account.get("display_name") or account_id),
            "active": bool(account.get("active", False)),
            "configured": account_id in configured,
            "status": str(account.get("status", "NOT_CHECKED")),
            "last_error": account.get("last_error"),
            "quota": account.get("quota"),
        }
