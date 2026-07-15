from __future__ import annotations

from datetime import date, datetime, time, timedelta
from typing import Any
from urllib.parse import quote

from .database import GatewayDatabase


class PlaybackIndex:
    """Build playback responses exclusively from the SQLite recording index."""

    def __init__(self, database: GatewayDatabase):
        self.database = database

    def days(self, camera_id: str, limit: int = 90) -> dict[str, Any]:
        if not camera_id.strip():
            raise ValueError("camera_id is required")
        return {
            "camera_id": camera_id,
            "days": self.database.playback_days(camera_id, limit),
        }

    def timeline(
        self,
        camera_id: str,
        day: str | None = None,
        from_ms: int | None = None,
        to_ms: int | None = None,
        limit: int = 500,
    ) -> dict[str, Any]:
        if not camera_id.strip():
            raise ValueError("camera_id is required")
        start_ms, end_ms, normalized_day = self._range(day, from_ms, to_ms)
        rows = self.database.playback_timeline(camera_id, start_ms, end_ms, limit)
        items = [self.item_payload(row) for row in rows]
        return {
            "camera_id": camera_id,
            "day": normalized_day,
            "from_ms": start_ms,
            "to_ms": end_ms,
            "count": len(items),
            "items": items,
        }

    def item(self, item_id: str) -> dict[str, Any] | None:
        row = self.database.get_clip(item_id)
        if row is None or row.get("clip_state") == "RECORDING":
            return None
        return self.item_payload(row)

    def sources(self, item_id: str) -> dict[str, Any] | None:
        row = self.database.get_clip(item_id)
        if row is None or row.get("clip_state") == "RECORDING":
            return None
        item = self.item_payload(row)
        local_ready = bool(item["local_available"])
        drive_ready = bool(item["drive_available"])
        youtube_ready = bool(item["youtube_available"])
        sources: list[dict[str, Any]] = [
            {
                "type": "LOCAL_CACHE",
                "state": "READY" if local_ready else "UNAVAILABLE",
                "stream_url": self._stream_url(item_id, "local") if local_ready else None,
            },
            {
                "type": "DRIVE_READY",
                "state": "READY" if drive_ready else "UNAVAILABLE",
                "remote_id": row.get("remote_id") if drive_ready else None,
                "file_id": row.get("remote_file_id") if drive_ready else None,
                "stream_url": self._stream_url(item_id, "drive") if drive_ready else None,
            },
            {
                "type": "YOUTUBE_READY",
                "state": "READY" if youtube_ready else str(row.get("youtube_state") or "NOT_CONFIGURED"),
                "video_id": item["youtube_video_id"],
                "start_offset_seconds": item["youtube_start_offset_seconds"],
                "watch_url": self.youtube_watch_url(row) if youtube_ready else None,
                "requires_google_sign_in": youtube_ready,
            },
        ]
        return {
            "item_id": item_id,
            "preferred_source": item["preferred_source"],
            "sources": sources,
        }

    def item_payload(self, row: dict) -> dict[str, Any]:
        duration_ms = int(row["duration_ms"]) if row.get("duration_ms") is not None else None
        start_ms = int(row["started_at_ms"])
        local_ready = row.get("local_state") == "AVAILABLE"
        drive_ready = (
            row.get("upload_state") == "UPLOADED"
            and bool(row.get("remote_id"))
            and bool(row.get("remote_path"))
            and bool(row.get("remote_file_id"))
            and row.get("remote_verified_at_ms") is not None
            and int(row.get("remote_size_bytes") or -1) == int(row.get("size_bytes") or 0)
        )
        youtube_ready = (
            row.get("youtube_state") == "YOUTUBE_READY"
            and bool(row.get("youtube_video_id"))
        )
        preferred = "LOCAL_CACHE" if local_ready else "DRIVE_READY" if drive_ready else "YOUTUBE_READY" if youtube_ready else None
        if preferred:
            status = "READY"
        elif row.get("youtube_state") in {"PENDING", "PROCESSING"} or row.get("clip_state") in {
            "LOCAL_PENDING", "DRIVE_UPLOADING", "UPLOAD_RETRY"
        }:
            status = "PROCESSING"
        elif row.get("clip_state") == "FAILED" or row.get("youtube_state") == "FAILED":
            status = "FAILED"
        else:
            status = "UNAVAILABLE"
        return {
            "id": str(row["id"]),
            "camera_id": str(row["camera_id"]),
            "start_time": start_ms,
            "end_time": start_ms + (duration_ms or 0),
            "duration": duration_ms,
            "motion": bool(row.get("motion")),
            "protected": bool(row.get("protected")),
            "local_available": local_ready,
            "drive_available": drive_ready,
            "youtube_available": youtube_ready,
            "youtube_video_id": row.get("youtube_video_id") if youtube_ready else None,
            "youtube_start_offset_seconds": max(0, int(row.get("youtube_start_offset_seconds") or 0)),
            "status": status,
            "preferred_source": preferred,
            "size_bytes": int(row.get("size_bytes") or 0),
            "last_error": row.get("last_error") or row.get("youtube_last_error"),
        }

    @staticmethod
    def youtube_watch_url(row: dict) -> str:
        video_id = quote(str(row.get("youtube_video_id") or ""), safe="")
        offset = max(0, int(row.get("youtube_start_offset_seconds") or 0))
        return f"https://www.youtube.com/watch?v={video_id}&t={offset}s"

    @staticmethod
    def _stream_url(item_id: str, source: str) -> str:
        return f"/api/playback/items/{quote(item_id, safe='')}/stream?source={source}"

    @staticmethod
    def _range(
        day: str | None, from_ms: int | None, to_ms: int | None
    ) -> tuple[int, int, str]:
        if from_ms is not None or to_ms is not None:
            if from_ms is None or to_ms is None or from_ms >= to_ms:
                raise ValueError("from_ms and to_ms must define a valid range")
            local_day = datetime.fromtimestamp(from_ms / 1000).date().isoformat()
            return int(from_ms), int(to_ms), local_day
        try:
            selected = date.fromisoformat(str(day or ""))
        except ValueError as error:
            raise ValueError("day must use YYYY-MM-DD") from error
        start = datetime.combine(selected, time.min).astimezone()
        end = datetime.combine(selected + timedelta(days=1), time.min).astimezone()
        return int(start.timestamp() * 1000), int(end.timestamp() * 1000), selected.isoformat()
