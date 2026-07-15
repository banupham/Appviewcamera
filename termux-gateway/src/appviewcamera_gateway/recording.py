from __future__ import annotations

import hashlib
import json
import asyncio
import logging
import shutil
import subprocess
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

from .config import GatewaySettings, _atomic_json, _read_json
from .database import GatewayDatabase
from .storage import GoogleDriveStore


LOGGER = logging.getLogger("appviewcamera.recording")


def load_recording_config(settings: GatewaySettings) -> dict[str, Any]:
    raw = _read_json(settings.home / "config" / "recording.json", {})
    recording = raw.get("recording", {}) if isinstance(raw, dict) else {}
    if "local_cache_retention_minutes" in recording:
        cache_retention_minutes = int(recording["local_cache_retention_minutes"])
    elif "uploaded_local_retention_minutes" in recording:
        cache_retention_minutes = int(recording["uploaded_local_retention_minutes"])
    elif "keep_uploaded_local_hours" in recording:
        legacy_hours = int(recording["keep_uploaded_local_hours"])
        legacy_minutes = int(recording.get("local_retention_minutes", 60))
        # Stock config before Drive-primary used 12h/60m; migrate that default to 6h.
        cache_retention_minutes = 360 if (legacy_hours, legacy_minutes) == (12, 60) else legacy_hours * 60
    else:
        cache_retention_minutes = int(recording.get("local_retention_minutes", 360))
    return {
        "enabled": bool(recording.get("enabled", False)),
        "root": str(recording.get("root", "recordings")),
        "segment_duration_seconds": max(10, min(3600, int(recording.get("segment_duration_seconds", 60)))),
        "part_duration_seconds": max(1, min(10, int(recording.get("part_duration_seconds", 1)))),
        "local_retention_minutes": max(5, min(10080, cache_retention_minutes)),
        "local_cache_retention_minutes": max(5, min(10080, cache_retention_minutes)),
        "local_min_free_bytes": max(256 * 1024**2, int(recording.get("local_min_free_bytes", 2 * 1024**3))),
        "local_cleanup_start_percent": max(50, min(98, int(recording.get("local_cleanup_start_percent", 85)))),
        "playback_cache_max_bytes": max(128 * 1024**2, int(recording.get("playback_cache_max_bytes", 2 * 1024**3))),
        "playback_cache_retention_minutes": max(5, min(1440, int(recording.get("playback_cache_retention_minutes", 60)))),
        "prefer_substream": bool(recording.get("prefer_substream", True)),
    }


class RecordingManager:
    def __init__(self, settings: GatewaySettings, database: GatewayDatabase):
        self.settings = settings
        self.database = database
        self.config_path = settings.home / "config" / "recording.json"
        self._playback_lock = threading.RLock()

    def config(self) -> dict[str, Any]:
        return load_recording_config(self.settings)

    def update(self, enabled: bool, local_retention_minutes: int | None = None) -> dict[str, Any]:
        raw = _read_json(self.config_path, {})
        if not isinstance(raw, dict):
            raw = {}
        recording = raw.setdefault("recording", {})
        recording["enabled"] = bool(enabled)
        if local_retention_minutes is not None:
            value = max(5, min(10080, int(local_retention_minutes)))
            recording["local_retention_minutes"] = value
            recording["local_cache_retention_minutes"] = value
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
            "storage_mode": "DRIVE_PRIMARY",
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
            seen.add(relative)
            # Index segment đang ghi, nhưng chỉ cho upload sau khi file đã ổn định.
            is_recording = now_ns - stat.st_mtime_ns < 2_000_000_000
            existing = self.database.find_clip_by_path(relative)
            if (
                existing
                and existing["size_bytes"] == stat.st_size
                and existing["modified_ns"] == stat.st_mtime_ns
                and existing.get("clip_state") != "RECORDING"
            ):
                continue
            started_at_ms = self._parse_started_at(path) or int(stat.st_mtime * 1000)
            clip = {
                "id": hashlib.sha256(relative.encode("utf-8")).hexdigest()[:24],
                "camera_id": camera_id,
                "relative_path": relative,
                "started_at_ms": started_at_ms,
                "duration_ms": None if is_recording else self._probe_duration_ms(path),
                "size_bytes": stat.st_size,
                "modified_ns": stat.st_mtime_ns,
                "clip_state": "RECORDING" if is_recording else "LOCAL_PENDING",
            }
            self.database.upsert_clip(clip)
        self.database.mark_missing_clips(seen)
        return self.database.list_clips(limit=200)

    def clip_path(self, clip_id: str) -> Path | None:
        clip = self.database.get_clip(clip_id)
        if not clip:
            return None
        path = (self.root / str(clip["relative_path"])).resolve()
        if self.root not in path.parents or not path.is_file():
            return None
        return path

    def playback_path(self, clip_id: str, drive_store: GoogleDriveStore) -> Path | None:
        local = self.clip_path(clip_id)
        if local is not None:
            return local
        clip = self.database.get_clip(clip_id)
        if not clip or (
            clip.get("clip_state") not in ("DRIVE_READY", "LOCAL_CACHE")
            and clip.get("upload_state") != "UPLOADED"
        ):
            return None
        remote_id = str(clip.get("remote_id") or "")
        remote_path = str(clip.get("remote_path") or "")
        if not remote_id or not remote_path:
            return None
        cache = self.settings.home / "cache" / "playback" / f"{clip_id}.mp4"
        with self._playback_lock:
            if cache.is_file() and cache.stat().st_size > 0:
                cache.touch()
                return cache
            drive_store.download_file(remote_id, remote_path, cache)
            self._trim_playback_cache(
                keep=cache,
                max_bytes=self.config()["playback_cache_max_bytes"],
                max_age_minutes=self.config()["playback_cache_retention_minutes"],
            )
            return cache

    def _trim_playback_cache(
        self, keep: Path, max_bytes: int = 2 * 1024**3, max_age_minutes: int = 60
    ) -> None:
        cache_root = keep.parent
        files = [path for path in cache_root.glob("*.mp4") if path.is_file()]
        cutoff_ns = time.time_ns() - max_age_minutes * 60 * 1_000_000_000
        for path in files:
            if path != keep and path.stat().st_mtime_ns < cutoff_ns:
                path.unlink(missing_ok=True)
        files = [path for path in cache_root.glob("*.mp4") if path.is_file()]
        total = sum(path.stat().st_size for path in files)
        for path in sorted(files, key=lambda item: item.stat().st_mtime_ns):
            if total <= max_bytes:
                break
            if path == keep:
                continue
            try:
                size = path.stat().st_size
                path.unlink()
                total -= size
            except OSError:
                continue

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


class RecordingWorker:
    """Indexes clips, uploads them durably and applies safe local retention."""

    def __init__(
        self,
        manager: RecordingManager,
        database: GatewayDatabase,
        drive_store: GoogleDriveStore,
        camera_provider,
    ):
        self.manager = manager
        self.database = database
        self.drive_store = drive_store
        self.camera_provider = camera_provider
        self.task: asyncio.Task | None = None
        self.last_error: str | None = None
        self.cleanup_remotes: set[str] = set()

    def start(self) -> None:
        self.database.reset_interrupted_uploads()
        self.task = asyncio.create_task(self._run(), name="recording-upload")

    async def stop(self) -> None:
        if self.task:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
            self.task = None

    def status(self) -> dict[str, Any]:
        return {
            "upload_counts": self.database.upload_counts(),
            "last_upload_error": self.last_error,
        }

    async def _run(self) -> None:
        await asyncio.sleep(3)
        while True:
            try:
                await asyncio.to_thread(self.run_once)
                self.last_error = None
            except Exception as error:
                self.last_error = str(error)
                LOGGER.exception("Recording worker failed")
            await asyncio.sleep(15)

    def run_once(self) -> None:
        self.manager.scan(self.camera_provider())
        self.database.apply_motion_events(int(time.time() * 1000))
        account = self.drive_store.upload_account()
        if account:
            pending = self.database.pending_clips(int(time.time() * 1000), limit=1)
            if pending:
                self._upload(pending[0], str(account["id"]))
        self._apply_retention()
        self._apply_remote_retention()

    def _upload(self, clip: dict, remote_id: str) -> None:
        local_path = self.manager.clip_path(str(clip["id"]))
        if local_path is None:
            self.database.mark_local_missing(str(clip["id"]))
            return
        remote_path = f"{self.drive_store.remote_root()}/{clip['relative_path']}"
        self.database.mark_uploading(str(clip["id"]), remote_id, remote_path)
        try:
            verification = self.drive_store.upload_file(remote_id, local_path, remote_path)
            uploaded_at_ms = int(time.time() * 1000)
            self.database.mark_uploaded(
                str(clip["id"]),
                uploaded_at_ms,
                remote_file_id=str(verification["file_id"]),
                remote_size_bytes=int(verification["size_bytes"]),
                verified_at_ms=int(verification["verified_at_ms"]),
            )
            self.drive_store.account_upload_completed(remote_id, int(clip["size_bytes"]))
        except Exception as error:
            attempts = int(clip.get("upload_attempts", 0)) + 1
            schedule = self.drive_store.retry_seconds()
            delay = schedule[min(attempts - 1, len(schedule) - 1)]
            self.database.mark_upload_failed(
                str(clip["id"]), str(error), int(time.time() * 1000) + delay * 1000
            )
            raise

    def _apply_retention(self) -> None:
        config = self.manager.config()
        retention_ms = config["local_cache_retention_minutes"] * 60_000
        before_ms = int(time.time() * 1000) - retention_ms
        for clip in self.database.uploaded_local_clips_before(before_ms):
            self._delete_verified_local(clip)

        usage = shutil.disk_usage(self.manager.settings.home)
        used_percent = int((usage.total - usage.free) * 100 / usage.total) if usage.total else 0
        if usage.free >= config["local_min_free_bytes"] and used_percent < config["local_cleanup_start_percent"]:
            return
        for clip in self.database.local_cache_clips(limit=500):
            if usage.free >= config["local_min_free_bytes"] and used_percent < config["local_cleanup_start_percent"]:
                break
            if self._delete_verified_local(clip):
                usage = shutil.disk_usage(self.manager.settings.home)
                used_percent = int((usage.total - usage.free) * 100 / usage.total) if usage.total else 0

    def _delete_verified_local(self, clip: dict) -> bool:
        if (
            clip.get("clip_state") != "LOCAL_CACHE"
            or not clip.get("remote_verified_at_ms")
            or int(clip.get("remote_size_bytes") or -1) != int(clip["size_bytes"])
        ):
            return False
        path = self.manager.clip_path(str(clip["id"]))
        if path is not None:
            try:
                path.unlink()
            except OSError as error:
                LOGGER.warning("Cannot remove verified local cache %s: %s", path, error)
                return False
        self.database.mark_local_missing(str(clip["id"]))
        return True

    def _apply_remote_retention(self) -> None:
        policy = self.drive_store.retention_policy()
        now_ms = int(time.time() * 1000)
        before_ms = now_ms - policy["minimum_retention_days"] * 86_400_000
        for account in self.drive_store.list():
            remote_id = str(account["id"])
            quota = account.get("quota") or {}
            total = int(quota.get("total") or 0)
            used = int(quota.get("used") or 0)
            if total <= 0:
                continue
            if used * 100 >= total * policy["cleanup_start_percent"]:
                self.cleanup_remotes.add(remote_id)
            if remote_id not in self.cleanup_remotes:
                continue
            if used * 100 <= total * policy["cleanup_target_percent"]:
                self.cleanup_remotes.discard(remote_id)
                continue
            candidates = self.database.remote_cleanup_candidates(
                remote_id, before_ms, limit=1
            )
            if not candidates:
                continue
            clip = candidates[0]
            self.drive_store.delete_file(remote_id, str(clip["remote_path"]))
            local = self.manager.clip_path(str(clip["id"]))
            if local is not None:
                local.unlink(missing_ok=True)
            self.database.record_deleted_clip(clip, "DRIVE_STORAGE_PRESSURE", now_ms)
            self.drive_store.account_file_deleted(remote_id, int(clip["size_bytes"]))
