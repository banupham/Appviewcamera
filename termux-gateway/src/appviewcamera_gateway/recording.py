from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Any

from .config import GatewaySettings, _atomic_json, _read_json
from .database import GatewayDatabase


def load_recording_config(settings: GatewaySettings) -> dict[str, Any]:
    raw = _read_json(settings.home / "config" / "recording.json", {})
    recording = raw.get("recording", {}) if isinstance(raw, dict) else {}
    return {
        "enabled": bool(recording.get("enabled", False)),
        "root": str(recording.get("root", "recordings")),
        "segment_duration_seconds": max(10, min(3600, int(recording.get("segment_duration_seconds", 60)))),
        "part_duration_seconds": max(1, min(10, int(recording.get("part_duration_seconds", 1)))),
        "local_retention_minutes": max(5, min(10080, int(recording.get("local_retention_minutes", 60)))),
        "prefer_substream": bool(recording.get("prefer_substream", True)),
    }


class RecordingManager:
    def __init__(self, settings: GatewaySettings, database: GatewayDatabase):
        self.settings = settings
        self.database = database
        self.config_path = settings.home / "config" / "recording.json"

    def config(self) -> dict[str, Any]:
        return load_recording_config(self.settings)

    def update(self, enabled: bool, local_retention_minutes: int | None = None) -> dict[str, Any]:
        raw = _read_json(self.config_path, {})
        if not isinstance(raw, dict):
            raw = {}
        recording = raw.setdefault("recording", {})
        recording["enabled"] = bool(enabled)
        if local_retention_minutes is not None:
            recording["local_retention_minutes"] = max(5, min(10080, int(local_retention_minutes)))
        _atomic_json(self.config_path, raw)
        return self.status()

    @property
    def root(self) -> Path:
        root = (self.settings.home / self.config()["root"]).resolve()
        if self.settings.home not in root.parents and root != self.settings.home:
            raise ValueError("Thư mục recording phải nằm trong APPVIEWCAMERA_HOME")
        root.mkdir(parents=True, exist_ok=True)
        return root

    def status(self) -> dict[str, Any]:
        config = self.config()
        usage = shutil.disk_usage(self.settings.home)
        return {
            **config,
            "clip_count": self.database.clip_count(),
            "disk_free_bytes": usage.free,
            "disk_total_bytes": usage.total,
        }

    def scan(self, cameras: list[dict]) -> list[dict]:
        root = self.root
        camera_ids = {str(camera.get("id")) for camera in cameras}
        seen: set[str] = set()
        now_ns = time.time_ns()
        for path in root.rglob("*.mp4"):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            camera_id = relative.split("/", 1)[0]
            if camera_id not in camera_ids:
                continue
            stat = path.stat()
            # Bỏ qua segment còn đang được MediaMTX ghi.
            if now_ns - stat.st_mtime_ns < 2_000_000_000:
                continue
            seen.add(relative)
            existing = self.database.find_clip_by_path(relative)
            if existing and existing["size_bytes"] == stat.st_size and existing["modified_ns"] == stat.st_mtime_ns:
                continue
            started_at_ms = self._parse_started_at(path) or int(stat.st_mtime * 1000)
            clip = {
                "id": hashlib.sha256(relative.encode("utf-8")).hexdigest()[:24],
                "camera_id": camera_id,
                "relative_path": relative,
                "started_at_ms": started_at_ms,
                "duration_ms": self._probe_duration_ms(path),
                "size_bytes": stat.st_size,
                "modified_ns": stat.st_mtime_ns,
            }
            self.database.upsert_clip(clip)
        self.database.delete_missing_clips(seen)
        return self.database.list_clips(limit=200)

    def clip_path(self, clip_id: str) -> Path | None:
        clip = self.database.get_clip(clip_id)
        if not clip:
            return None
        path = (self.root / str(clip["relative_path"])).resolve()
        if self.root not in path.parents or not path.is_file():
            return None
        return path

    @staticmethod
    def _parse_started_at(path: Path) -> int | None:
        try:
            value = datetime.strptime(path.stem, "%Y-%m-%d_%H-%M-%S-%f")
            return int(value.timestamp() * 1000)
        except ValueError:
            return None

    @staticmethod
    def _probe_duration_ms(path: Path) -> int | None:
        try:
            completed = subprocess.run(
                [
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "json", str(path),
                ],
                capture_output=True,
                text=True,
                timeout=20,
                check=False,
            )
            if completed.returncode != 0:
                return None
            duration = json.loads(completed.stdout).get("format", {}).get("duration")
            return max(0, int(float(duration) * 1000)) if duration is not None else None
        except (OSError, subprocess.TimeoutExpired, ValueError, json.JSONDecodeError):
            return None
