from __future__ import annotations

import json
import math
import subprocess
import time
import uuid
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

from .account import YouTubeConfig
from .index import YouTubeRepository


Runner = Callable[..., subprocess.CompletedProcess[str]]
AUTO_DURATIONS = (60, 90, 120)


def estimated_uploads_per_day(camera_count: int, target_duration_minutes: int) -> int:
    if camera_count <= 0:
        return 0
    return math.ceil(camera_count * 1440 / max(1, target_duration_minutes))


def quota_aware_target_minutes(
    camera_count: int,
    requested_minutes: int = 60,
    max_target_uploads_per_day: int = 80,
    upload_limit_per_day: int = 100,
) -> dict[str, Any]:
    requested = requested_minutes if requested_minutes in (60, 90, 120) else 60
    safe_limit = max(1, min(max_target_uploads_per_day, upload_limit_per_day))
    target = requested
    for candidate in AUTO_DURATIONS:
        if candidate < requested:
            continue
        target = candidate
        if estimated_uploads_per_day(camera_count, candidate) <= safe_limit:
            break
    estimate = estimated_uploads_per_day(camera_count, target)
    return {
        "requested_minutes": requested,
        "target_minutes": target,
        "estimated_uploads_per_day": estimate,
        "safe_upload_limit": safe_limit,
        "warning": (
            f"Estimated {estimated_uploads_per_day(camera_count, requested)} uploads/day; "
            f"batch duration increased to {target} minutes"
            if target != requested
            else None
        ),
    }


class YouTubeBatchBuilder:
    def __init__(
        self,
        repository: YouTubeRepository,
        recording_manager,
        drive_store,
        gateway_id: str,
        config_provider: Callable[[], YouTubeConfig],
        camera_provider,
        runner: Runner = subprocess.run,
    ):
        self.repository = repository
        self.recording_manager = recording_manager
        self.drive_store = drive_store
        self.gateway_id = gateway_id
        self.config_provider = config_provider
        self.camera_provider = camera_provider
        self.runner = runner

    def policy(self) -> dict[str, Any]:
        config = self.config_provider()
        return quota_aware_target_minutes(
            len([camera for camera in self.camera_provider() if camera.get("enabled", True)]),
            config.target_duration_minutes,
            config.max_target_uploads_per_day,
            config.upload_limit_per_day,
        )

    def build_next(self) -> dict[str, Any] | None:
        config = self.config_provider()
        if not config.enabled:
            return None
        policy = self.policy()
        target_ms = int(policy["target_minutes"]) * 60_000
        groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
        for clip in self.repository.eligible_clips():
            local_day = datetime.fromtimestamp(int(clip["started_at_ms"]) / 1000).date().isoformat()
            groups[(str(clip["camera_id"]), local_day)].append(clip)
        for (camera_id, local_day), clips in sorted(groups.items(), key=lambda item: item[1][0]["started_at_ms"]):
            if self.repository.uploads_for_day(local_day) >= int(policy["safe_upload_limit"]):
                continue
            selected: list[dict[str, Any]] = []
            total_ms = 0
            previous_end: int | None = None
            for clip in clips:
                start = int(clip["started_at_ms"])
                duration = int(clip.get("duration_ms") or 0)
                if previous_end is not None and start - previous_end > 5 * 60_000:
                    selected, total_ms = [], 0
                selected.append(clip)
                total_ms += duration
                previous_end = start + duration
                if total_ms >= target_ms:
                    break
            if total_ms < target_ms or len(selected) < 2:
                continue
            start_ms = int(selected[0]["started_at_ms"])
            end_ms = int(selected[-1]["started_at_ms"]) + int(selected[-1]["duration_ms"])
            batch_id = uuid.uuid4().hex
            batch = {
                "id": batch_id,
                "camera_id": camera_id,
                "local_day": local_day,
                "title": self._title(camera_id, start_ms, end_ms),
                "description": self._description(camera_id, start_ms, end_ms),
                "start_time_ms": start_ms,
                "end_time_ms": end_ms,
                "duration_ms": total_ms,
                "target_duration_minutes": int(policy["target_minutes"]),
            }
            self.repository.create_batch(batch, [str(clip["id"]) for clip in selected])
            try:
                output = self._stream_copy(batch_id, selected, config.batch_root)
                return self.repository.mark_batch_file(batch_id, str(output), output.stat().st_size)
            except Exception as error:
                self.repository.mark_failed(batch_id, f"Safe stream-copy batch failed: {error}")
                return None
        return None

    def _stream_copy(self, batch_id: str, clips: list[dict[str, Any]], root: Path) -> Path:
        paths: list[Path] = []
        signatures = []
        for clip in clips:
            path = self.recording_manager.playback_path(
                str(clip["id"]), self.drive_store, "auto"
            )
            if path is None:
                raise RuntimeError(f"Drive source is unavailable for clip {clip['id']}")
            paths.append(path)
            signatures.append(self._probe_signature(path))
        if not signatures or any(signature != signatures[0] for signature in signatures[1:]):
            raise RuntimeError("codec/container parameters are not compatible for stream copy")
        batch_dir = root / batch_id
        batch_dir.mkdir(parents=True, exist_ok=True)
        concat_file = batch_dir / "segments.txt"
        concat_file.write_text(
            "".join(f"file '{str(path.resolve()).replace(chr(39), chr(39) + chr(92) + chr(39) + chr(39))}'\n" for path in paths),
            encoding="utf-8",
        )
        temporary = batch_dir / "archive.mp4.part"
        output = batch_dir / "archive.mp4"
        completed = self.runner(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(concat_file),
            "-map",
            "0",
            "-c",
            "copy",
            "-movflags",
            "+faststart",
            "-f",
            "mp4",
            "-y",
            str(temporary),
        ],
            capture_output=True,
            text=True,
            timeout=max(300, len(paths) * 15),
            check=False,
        )
        if completed.returncode != 0 or not temporary.is_file() or temporary.stat().st_size <= 0:
            raise RuntimeError((completed.stderr or completed.stdout or "ffmpeg did not create output")[-500:])
        temporary.replace(output)
        return output

    def _probe_signature(self, path: Path) -> tuple[Any, ...]:
        completed = self.runner(
            [
                "ffprobe", "-v", "error", "-show_entries",
                "format=format_name:stream=index,codec_type,codec_name,width,height,sample_rate,channels",
                "-of", "json", str(path),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        if completed.returncode != 0:
            raise RuntimeError((completed.stderr or "ffprobe failed")[-500:])
        payload = json.loads(completed.stdout)
        format_names = set(str(payload.get("format", {}).get("format_name", "")).split(","))
        if not format_names.intersection({"mov", "mp4", "m4a", "3gp", "3g2", "mj2"}):
            raise RuntimeError("segment container is not MP4-compatible")
        streams = tuple(
            (
                item.get("codec_type"), item.get("codec_name"), item.get("width"), item.get("height"),
                item.get("sample_rate"), item.get("channels"),
            )
            for item in payload.get("streams", [])
        )
        if not streams:
            raise RuntimeError("segment has no media stream")
        return streams

    def _title(self, camera_id: str, start_ms: int, end_ms: int) -> str:
        start = datetime.fromtimestamp(start_ms / 1000)
        end = datetime.fromtimestamp(end_ms / 1000)
        return f"{camera_id.upper()}_{start:%Y-%m-%d_%H-%M}_to_{end:%H-%M}"

    def _description(self, camera_id: str, start_ms: int, end_ms: int) -> str:
        start = datetime.fromtimestamp(start_ms / 1000).isoformat(timespec="seconds")
        end = datetime.fromtimestamp(end_ms / 1000).isoformat(timespec="seconds")
        return f"Gateway ID: {self.gateway_id}\nCamera ID: {camera_id}\nStart: {start}\nEnd: {end}"
