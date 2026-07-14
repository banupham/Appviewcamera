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
