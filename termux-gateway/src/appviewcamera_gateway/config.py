from __future__ import annotations

import os
import json
import re
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

CAMERA_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")


def _read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    return default if value is None else value


def _atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_secrets(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#") or "=" not in raw_line:
            continue
        key, value = raw_line.split("=", 1)
        values[key.strip()] = value
    return values


def write_secrets(path: Path, values: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if any("\n" in key or "=" in key or "\n" in value for key, value in values.items()):
        raise ValueError("secret không được chứa ký tự xuống dòng; tên secret không được chứa dấu =")
    content = "".join(f"{key}={values[key]}\n" for key in sorted(values))
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@dataclass(frozen=True)
class GatewaySettings:
    home: Path
    api_host: str
    api_port: int
    database_path: Path
    mediamtx_binary: Path
    mediamtx_config: Path
    mediamtx_rtsp_port: int
    discovery_enabled: bool
    discovery_interval_seconds: int
    discovery_subnets: tuple[str, ...]
    discovery_ports: tuple[int, ...]
    discovery_timeout_seconds: float
    discovery_max_hosts: int
    secrets_path: Path

    @classmethod
    def load(cls, home: Path | None = None) -> "GatewaySettings":
        root = Path(home or os.environ.get("APPVIEWCAMERA_HOME", "~/appviewcamera")).expanduser().resolve()
        raw = _read_json(root / "config" / "gateway.json", {})
        api = raw.get("api", {})
        database = raw.get("database", {})
        mediamtx = raw.get("mediamtx", {})
        discovery = raw.get("discovery", {})
        return cls(
            home=root,
            api_host=str(api.get("host", "0.0.0.0")),
            api_port=int(api.get("port", 8080)),
            database_path=root / str(database.get("path", "data/gateway.db")),
            mediamtx_binary=root / str(mediamtx.get("binary", "bin/mediamtx")),
            mediamtx_config=root / str(mediamtx.get("generated_config", "run/mediamtx.yml")),
            mediamtx_rtsp_port=int(mediamtx.get("rtsp_port", 8554)),
            discovery_enabled=bool(discovery.get("enabled", True)),
            discovery_interval_seconds=max(60, int(discovery.get("interval_seconds", 900))),
            discovery_subnets=tuple(str(item) for item in discovery.get("subnets", [])),
            discovery_ports=tuple(int(item) for item in discovery.get("ports", [554, 80, 443, 8000, 8554])),
            discovery_timeout_seconds=max(0.1, float(discovery.get("timeout_seconds", 0.6))),
            discovery_max_hosts=max(1, min(1024, int(discovery.get("max_hosts", 256)))),
            secrets_path=root / str(raw.get("secrets_file", "config/secrets.env")),
        )

    @property
    def api_token(self) -> str:
        return read_secrets(self.secrets_path).get("API_TOKEN", "")


class CameraStore:
    def __init__(self, settings: GatewaySettings):
        self.settings = settings
        self.path = settings.home / "config" / "cameras.json"
        self._lock = threading.RLock()

    def list(self) -> list[dict[str, Any]]:
        with self._lock:
            raw = _read_json(self.path, {"cameras": []})
            cameras = raw.get("cameras", []) if isinstance(raw, dict) else []
            return [dict(camera) for camera in cameras if isinstance(camera, dict)]

    def upsert(self, camera: dict[str, Any], password: str | None = None) -> dict[str, Any]:
        camera_id = str(camera.get("id", "")).lower().strip()
        if not CAMERA_ID_PATTERN.fullmatch(camera_id):
            raise ValueError("id camera chỉ gồm a-z, 0-9, _ hoặc -, tối đa 32 ký tự")
        normalized = {
            "id": camera_id,
            "name": str(camera.get("name") or camera_id),
            "host": str(camera.get("host", "")).strip(),
            "port": int(camera.get("port", 554)),
            "username": str(camera.get("username", "")).strip(),
            "main_path": str(camera.get("main_path", "")).strip(),
            "sub_path": str(camera.get("sub_path", "")).strip(),
            "relay_path": str(camera.get("relay_path") or camera_id).strip("/"),
            "enabled": bool(camera.get("enabled", True)),
            "record_enabled": bool(camera.get("record_enabled", True)),
            "motion_enabled": bool(camera.get("motion_enabled", False)),
            "audio_enabled": bool(camera.get("audio_enabled", True)),
            "secret_ref": f"CAMERA_{camera_id.upper().replace('-', '_')}_PASSWORD",
        }
        if not normalized["host"] or not normalized["main_path"]:
            raise ValueError("host và main_path là bắt buộc")
        if not 1 <= normalized["port"] <= 65535:
            raise ValueError("port phải nằm trong khoảng 1..65535")
        with self._lock:
            cameras = self.list()
            cameras = [item for item in cameras if item.get("id") != camera_id]
            cameras.append(normalized)
            _atomic_json(self.path, {"cameras": cameras})
            if password is not None:
                secrets = read_secrets(self.settings.secrets_path)
                secrets[normalized["secret_ref"]] = password
                write_secrets(self.settings.secrets_path, secrets)
        return normalized

    def delete(self, camera_id: str) -> bool:
        with self._lock:
            cameras = self.list()
            removed = next((item for item in cameras if item.get("id") == camera_id), None)
            if removed is None:
                return False
            _atomic_json(self.path, {"cameras": [item for item in cameras if item.get("id") != camera_id]})
            secret_ref = removed.get("secret_ref")
            if secret_ref:
                secrets = read_secrets(self.settings.secrets_path)
                secrets.pop(str(secret_ref), None)
                write_secrets(self.settings.secrets_path, secrets)
            return True
