from __future__ import annotations

import sqlite3
import threading
from pathlib import Path
from typing import Iterable


SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS discovery_candidates (
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    source TEXT NOT NULL,
    service_url TEXT,
    onvif_uuid TEXT,
    mac TEXT,
    first_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (host, port)
);
CREATE INDEX IF NOT EXISTS idx_discovery_last_seen ON discovery_candidates(last_seen);
CREATE TABLE IF NOT EXISTS recording_clips (
    id TEXT PRIMARY KEY,
    camera_id TEXT NOT NULL,
    relative_path TEXT NOT NULL UNIQUE,
    started_at_ms INTEGER NOT NULL,
    duration_ms INTEGER,
    size_bytes INTEGER NOT NULL,
    modified_ns INTEGER NOT NULL,
    clip_state TEXT NOT NULL DEFAULT 'LOCAL_PENDING',
    local_state TEXT NOT NULL DEFAULT 'AVAILABLE',
    upload_state TEXT NOT NULL DEFAULT 'PENDING',
    remote_id TEXT,
    remote_path TEXT,
    remote_file_id TEXT,
    remote_size_bytes INTEGER,
    remote_verified_at_ms INTEGER,
    youtube_state TEXT NOT NULL DEFAULT 'NOT_CONFIGURED',
    youtube_status TEXT NOT NULL DEFAULT 'NOT_CONFIGURED',
    youtube_video_id TEXT,
    youtube_batch_id TEXT,
    youtube_start_offset_seconds INTEGER NOT NULL DEFAULT 0,
    youtube_end_offset_seconds INTEGER NOT NULL DEFAULT 0,
    youtube_uploaded_at INTEGER,
    youtube_processing_status TEXT,
    youtube_updated_at_ms INTEGER,
    youtube_last_error TEXT,
    upload_attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_ms INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    uploaded_at_ms INTEGER,
    local_cached_at_ms INTEGER,
    local_deleted_at_ms INTEGER,
    state_updated_at_ms INTEGER NOT NULL DEFAULT 0,
    protected INTEGER NOT NULL DEFAULT 0,
    motion INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_recording_camera_time
ON recording_clips(camera_id, started_at_ms DESC);
CREATE TABLE IF NOT EXISTS motion_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    camera_id TEXT NOT NULL,
    detected_at_ms INTEGER NOT NULL,
    processed INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_motion_events_pending
ON motion_events(processed, detected_at_ms);
CREATE TABLE IF NOT EXISTS deletion_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    clip_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    remote_id TEXT,
    reason TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    deleted_at_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS youtube_accounts (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    token_json TEXT NOT NULL,
    scope TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'NOT_CHECKED',
    last_error TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS youtube_batches (
    id TEXT PRIMARY KEY,
    camera_id TEXT NOT NULL,
    local_day TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    start_time_ms INTEGER NOT NULL,
    end_time_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    target_duration_minutes INTEGER NOT NULL,
    local_path TEXT,
    state TEXT NOT NULL DEFAULT 'YOUTUBE_PENDING',
    account_id TEXT,
    youtube_video_id TEXT,
    last_error TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_youtube_batches_state
ON youtube_batches(state, start_time_ms);
CREATE TABLE IF NOT EXISTS youtube_upload_jobs (
    id TEXT PRIMARY KEY,
    batch_id TEXT NOT NULL UNIQUE,
    account_id TEXT,
    state TEXT NOT NULL DEFAULT 'YOUTUBE_PENDING',
    upload_url TEXT,
    uploaded_bytes INTEGER NOT NULL DEFAULT 0,
    total_bytes INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_ms INTEGER NOT NULL DEFAULT 0,
    youtube_video_id TEXT,
    last_error TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    FOREIGN KEY(batch_id) REFERENCES youtube_batches(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_youtube_jobs_retry
ON youtube_upload_jobs(state, next_retry_ms, created_at_ms);
"""


class GatewayDatabase:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        with self.connect() as connection:
            connection.executescript(SCHEMA)
            self._migrate_discovery_candidates(connection)
            self._migrate_recording_clips(connection)

    @staticmethod
    def _migrate_discovery_candidates(connection: sqlite3.Connection) -> None:
        columns = {
            str(row[1])
            for row in connection.execute("PRAGMA table_info(discovery_candidates)").fetchall()
        }
        for name in ("onvif_uuid", "mac"):
            if name not in columns:
                connection.execute(f"ALTER TABLE discovery_candidates ADD COLUMN {name} TEXT")

    @staticmethod
    def _migrate_recording_clips(connection: sqlite3.Connection) -> None:
        columns = {
            str(row[1])
            for row in connection.execute("PRAGMA table_info(recording_clips)").fetchall()
        }
        needs_state_migration = "clip_state" not in columns
        migrations = {
            "clip_state": "TEXT NOT NULL DEFAULT 'LOCAL_PENDING'",
            "local_state": "TEXT NOT NULL DEFAULT 'AVAILABLE'",
            "upload_state": "TEXT NOT NULL DEFAULT 'PENDING'",
            "remote_id": "TEXT",
            "remote_path": "TEXT",
            "remote_file_id": "TEXT",
            "remote_size_bytes": "INTEGER",
            "remote_verified_at_ms": "INTEGER",
            "youtube_state": "TEXT NOT NULL DEFAULT 'NOT_CONFIGURED'",
            "youtube_status": "TEXT NOT NULL DEFAULT 'NOT_CONFIGURED'",
            "youtube_video_id": "TEXT",
            "youtube_batch_id": "TEXT",
            "youtube_start_offset_seconds": "INTEGER NOT NULL DEFAULT 0",
            "youtube_end_offset_seconds": "INTEGER NOT NULL DEFAULT 0",
            "youtube_uploaded_at": "INTEGER",
            "youtube_processing_status": "TEXT",
            "youtube_updated_at_ms": "INTEGER",
            "youtube_last_error": "TEXT",
            "upload_attempts": "INTEGER NOT NULL DEFAULT 0",
            "next_retry_ms": "INTEGER NOT NULL DEFAULT 0",
            "last_error": "TEXT",
            "uploaded_at_ms": "INTEGER",
            "local_cached_at_ms": "INTEGER",
            "local_deleted_at_ms": "INTEGER",
            "state_updated_at_ms": "INTEGER NOT NULL DEFAULT 0",
            "protected": "INTEGER NOT NULL DEFAULT 0",
            "motion": "INTEGER NOT NULL DEFAULT 0",
        }
        for name, declaration in migrations.items():
            if name not in columns:
                connection.execute(
                    f"ALTER TABLE recording_clips ADD COLUMN {name} {declaration}"
                )
        if needs_state_migration:
            connection.execute(
                """
                UPDATE recording_clips SET
                    clip_state=CASE
                        WHEN upload_state='UPLOADED' AND local_state='AVAILABLE' THEN 'LOCAL_CACHE'
                        WHEN upload_state='UPLOADED' THEN 'DRIVE_READY'
                        WHEN upload_state='UPLOADING' THEN 'DRIVE_UPLOADING'
                        WHEN upload_state='FAILED' AND local_state='AVAILABLE' THEN 'UPLOAD_RETRY'
                        WHEN local_state='AVAILABLE' THEN 'LOCAL_PENDING'
                        ELSE 'FAILED'
                    END,
                    remote_file_id=CASE
                        WHEN upload_state='UPLOADED' THEN 'legacy-path:' || remote_path
                        ELSE NULL
                    END,
                    remote_size_bytes=CASE WHEN upload_state='UPLOADED' THEN size_bytes ELSE NULL END,
                    remote_verified_at_ms=CASE
                        WHEN upload_state='UPLOADED' THEN COALESCE(uploaded_at_ms, started_at_ms)
                        ELSE NULL
                    END,
                    local_cached_at_ms=CASE
                        WHEN upload_state='UPLOADED' AND local_state='AVAILABLE'
                            THEN COALESCE(uploaded_at_ms, started_at_ms)
                        ELSE NULL
                    END,
                    state_updated_at_ms=COALESCE(uploaded_at_ms, started_at_ms)
                """
            )
        connection.execute(
            "CREATE INDEX IF NOT EXISTS idx_recording_clip_state_retry "
            "ON recording_clips(clip_state, next_retry_ms, started_at_ms)"
        )
        connection.execute(
            "CREATE INDEX IF NOT EXISTS idx_recording_camera_day "
            "ON recording_clips(camera_id, started_at_ms, clip_state)"
        )
        connection.execute(
            "UPDATE recording_clips SET youtube_status=youtube_state "
            "WHERE youtube_status='NOT_CONFIGURED' AND youtube_state!='NOT_CONFIGURED'"
        )
        connection.execute(
            "CREATE INDEX IF NOT EXISTS idx_recording_youtube_batch "
            "ON recording_clips(youtube_batch_id, youtube_status, camera_id, started_at_ms)"
        )

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        return connection

    def save_candidates(self, candidates: Iterable[dict]) -> None:
        rows = [
            {
                **candidate,
                "service_url": candidate.get("service_url"),
                "onvif_uuid": candidate.get("onvif_uuid"),
                "mac": candidate.get("mac"),
            }
            for candidate in candidates
        ]
        with self._lock, self.connect() as connection:
            connection.executemany(
                """
                INSERT INTO discovery_candidates(host, port, source, service_url, onvif_uuid, mac)
                VALUES (:host, :port, :source, :service_url, :onvif_uuid, :mac)
                ON CONFLICT(host, port) DO UPDATE SET
                    source=excluded.source,
                    service_url=COALESCE(excluded.service_url, discovery_candidates.service_url),
                    onvif_uuid=COALESCE(excluded.onvif_uuid, discovery_candidates.onvif_uuid),
                    mac=COALESCE(excluded.mac, discovery_candidates.mac),
                    last_seen=CURRENT_TIMESTAMP
                """,
                rows,
            )

    def list_candidates(self) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT host, port, source, service_url, onvif_uuid, mac, first_seen, last_seen "
                "FROM discovery_candidates ORDER BY last_seen DESC, host, port"
            ).fetchall()
        return [dict(row) for row in rows]

    def find_candidate(self, host: str, port: int) -> dict | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT host, port, source, service_url, onvif_uuid, mac "
                "FROM discovery_candidates WHERE lower(host)=lower(?) AND port=?",
                (host, port),
            ).fetchone()
        return dict(row) if row else None

    def find_clip_by_path(self, relative_path: str) -> dict | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT * FROM recording_clips WHERE relative_path=?", (relative_path,)
            ).fetchone()
        return dict(row) if row else None

    def upsert_clip(self, clip: dict) -> None:
        item = {**clip, "clip_state": str(clip.get("clip_state") or "LOCAL_PENDING")}
        with self._lock, self.connect() as connection:
            connection.execute(
                """
                INSERT INTO recording_clips(
                    id, camera_id, relative_path, started_at_ms,
                    duration_ms, size_bytes, modified_ns, clip_state, state_updated_at_ms
                ) VALUES (
                    :id, :camera_id, :relative_path, :started_at_ms,
                    :duration_ms, :size_bytes, :modified_ns, :clip_state, :started_at_ms
                )
                ON CONFLICT(relative_path) DO UPDATE SET
                    camera_id=excluded.camera_id,
                    started_at_ms=excluded.started_at_ms,
                    duration_ms=excluded.duration_ms,
                    size_bytes=excluded.size_bytes,
                    modified_ns=excluded.modified_ns,
                    local_state='AVAILABLE',
                    local_deleted_at_ms=NULL,
                    clip_state=CASE
                        WHEN recording_clips.clip_state IN ('DRIVE_READY', 'LOCAL_CACHE') THEN 'LOCAL_CACHE'
                        ELSE excluded.clip_state
                    END,
                    state_updated_at_ms=excluded.state_updated_at_ms
                """,
                item,
            )

    def mark_missing_clips(self, relative_paths: set[str]) -> None:
        with self._lock, self.connect() as connection:
            indexed = {
                str(row["relative_path"])
                for row in connection.execute("SELECT relative_path FROM recording_clips").fetchall()
            }
            missing = indexed - relative_paths
            if missing:
                connection.executemany(
                    """
                    UPDATE recording_clips SET
                        local_state='MISSING',
                        upload_state=CASE
                            WHEN upload_state='UPLOADED' AND remote_verified_at_ms IS NOT NULL
                                THEN 'UPLOADED'
                            ELSE 'FAILED'
                        END,
                        clip_state=CASE
                            WHEN upload_state='UPLOADED' AND remote_verified_at_ms IS NOT NULL
                                THEN 'DRIVE_READY'
                            ELSE 'FAILED'
                        END,
                        last_error=CASE
                            WHEN upload_state='UPLOADED' AND remote_verified_at_ms IS NOT NULL
                                THEN last_error
                            ELSE 'Local staging file disappeared before verified Drive upload'
                        END,
                        state_updated_at_ms=CAST(strftime('%s','now') AS INTEGER) * 1000
                    WHERE relative_path=?
                    """,
                    ((path,) for path in missing),
                )

    def list_clips(
        self,
        camera_id: str | None = None,
        from_ms: int | None = None,
        to_ms: int | None = None,
        limit: int = 200,
    ) -> list[dict]:
        clauses: list[str] = ["clip_state!='RECORDING'"]
        values: list[object] = []
        if camera_id:
            clauses.append("camera_id=?")
            values.append(camera_id)
        if from_ms is not None:
            clauses.append("started_at_ms>=?")
            values.append(from_ms)
        if to_ms is not None:
            clauses.append("started_at_ms<=?")
            values.append(to_ms)
        where = " WHERE " + " AND ".join(clauses) if clauses else ""
        values.append(max(1, min(500, limit)))
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT id, camera_id, started_at_ms, duration_ms, size_bytes, "
                "clip_state, local_state, upload_state, remote_id, remote_path, remote_file_id, "
                "remote_size_bytes, remote_verified_at_ms, uploaded_at_ms, upload_attempts, "
                "next_retry_ms, last_error, protected, motion "
                f"FROM recording_clips{where} ORDER BY started_at_ms DESC LIMIT ?",
                values,
            ).fetchall()
        return [dict(row) for row in rows]

    def get_clip(self, clip_id: str) -> dict | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT * FROM recording_clips WHERE id=?", (clip_id,)
            ).fetchone()
        return dict(row) if row else None

    def playback_days(self, camera_id: str, limit: int = 90) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT date(started_at_ms / 1000, 'unixepoch', 'localtime') AS day, "
                "COUNT(*) AS item_count, MIN(started_at_ms) AS first_start_time, "
                "MAX(started_at_ms + COALESCE(duration_ms, 0)) AS last_end_time "
                "FROM recording_clips WHERE camera_id=? AND clip_state!='RECORDING' "
                "GROUP BY day ORDER BY day DESC LIMIT ?",
                (camera_id, max(1, min(366, limit))),
            ).fetchall()
        return [dict(row) for row in rows]

    def playback_timeline(
        self, camera_id: str, from_ms: int, to_ms: int, limit: int = 500
    ) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips WHERE camera_id=? AND clip_state!='RECORDING' "
                "AND started_at_ms>=? AND started_at_ms<? "
                "ORDER BY started_at_ms LIMIT ?",
                (camera_id, from_ms, to_ms, max(1, min(1000, limit))),
            ).fetchall()
        return [dict(row) for row in rows]

    def set_youtube_source(
        self,
        clip_id: str,
        state: str,
        video_id: str | None = None,
        start_offset_seconds: int = 0,
        updated_at_ms: int | None = None,
        error: str | None = None,
    ) -> dict | None:
        normalized = state.strip().upper()
        if normalized not in {"NOT_CONFIGURED", "PENDING", "PROCESSING", "YOUTUBE_READY", "FAILED"}:
            raise ValueError("Invalid YouTube playback state")
        if normalized == "YOUTUBE_READY" and not (video_id or "").strip():
            raise ValueError("youtube_video_id is required for YOUTUBE_READY")
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET youtube_state=?, youtube_status=?, youtube_video_id=?, "
                "youtube_start_offset_seconds=?, youtube_updated_at_ms=?, youtube_last_error=? "
                "WHERE id=?",
                (
                    normalized,
                    normalized,
                    (video_id or "").strip() or None,
                    max(0, int(start_offset_seconds)),
                    updated_at_ms,
                    error[-500:] if error else None,
                    clip_id,
                ),
            )
            row = connection.execute(
                "SELECT * FROM recording_clips WHERE id=?", (clip_id,)
            ).fetchone()
        return dict(row) if row else None

    def clip_count(self) -> int:
        with self.connect() as connection:
            row = connection.execute("SELECT COUNT(*) AS count FROM recording_clips").fetchone()
        return int(row["count"])

    def last_drive_verified_at_ms(self) -> int | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT MAX(remote_verified_at_ms) value FROM recording_clips "
                "WHERE upload_state='UPLOADED'"
            ).fetchone()
        return int(row["value"]) if row and row["value"] is not None else None

    def pending_clips(self, now_ms: int, limit: int = 10) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips "
                "WHERE local_state='AVAILABLE' "
                "AND clip_state IN ('LOCAL_PENDING', 'UPLOAD_RETRY') AND next_retry_ms<=? "
                "ORDER BY started_at_ms LIMIT ?",
                (now_ms, max(1, min(100, limit))),
            ).fetchall()
        return [dict(row) for row in rows]

    def mark_uploading(self, clip_id: str, remote_id: str, remote_path: str) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET upload_state='UPLOADING', clip_state='DRIVE_UPLOADING', "
                "remote_id=?, remote_path=?, upload_attempts=upload_attempts+1, last_error=NULL, "
                "state_updated_at_ms=CAST(strftime('%s','now') AS INTEGER) * 1000 "
                "WHERE id=?",
                (remote_id, remote_path, clip_id),
            )

    def mark_uploaded(
        self,
        clip_id: str,
        uploaded_at_ms: int,
        remote_file_id: str | None = None,
        remote_size_bytes: int | None = None,
        verified_at_ms: int | None = None,
    ) -> None:
        with self._lock, self.connect() as connection:
            current = connection.execute(
                "SELECT remote_path, size_bytes FROM recording_clips WHERE id=?", (clip_id,)
            ).fetchone()
            if current is None:
                return
            file_id = remote_file_id or str(current["remote_path"] or "")
            verified_size = int(remote_size_bytes if remote_size_bytes is not None else current["size_bytes"])
            verified_at = int(verified_at_ms if verified_at_ms is not None else uploaded_at_ms)
            if not file_id:
                raise ValueError("remote_file_id is required before DRIVE_READY")
            if verified_size != int(current["size_bytes"]):
                raise ValueError("verified Drive size does not match local clip")
            connection.execute(
                "UPDATE recording_clips SET upload_state='UPLOADED', clip_state='LOCAL_CACHE', "
                "uploaded_at_ms=?, remote_file_id=?, remote_size_bytes=?, remote_verified_at_ms=?, "
                "local_cached_at_ms=?, next_retry_ms=0, last_error=NULL, state_updated_at_ms=? "
                "WHERE id=?",
                (uploaded_at_ms, file_id, verified_size, verified_at, uploaded_at_ms, uploaded_at_ms, clip_id),
            )

    def mark_upload_failed(self, clip_id: str, error: str, next_retry_ms: int) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET upload_state='FAILED', "
                "clip_state=CASE WHEN local_state='AVAILABLE' THEN 'UPLOAD_RETRY' ELSE 'FAILED' END, "
                "last_error=?, next_retry_ms=?, state_updated_at_ms=CAST(strftime('%s','now') AS INTEGER) * 1000 "
                "WHERE id=?",
                (error[-500:], next_retry_ms, clip_id),
            )

    def reset_interrupted_uploads(self) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET upload_state='FAILED', "
                "clip_state=CASE WHEN local_state='AVAILABLE' THEN 'UPLOAD_RETRY' ELSE 'FAILED' END, "
                "next_retry_ms=0, last_error='Gateway restarted during upload' "
                "WHERE upload_state='UPLOADING' OR clip_state='DRIVE_UPLOADING'"
            )

    def upload_counts(self) -> dict[str, int]:
        states = (
            "RECORDING", "LOCAL_PENDING", "DRIVE_UPLOADING", "DRIVE_READY",
            "LOCAL_CACHE", "UPLOAD_RETRY", "FAILED",
        )
        result = {state: 0 for state in states}
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT clip_state, COUNT(*) AS count FROM recording_clips GROUP BY clip_state"
            ).fetchall()
        for row in rows:
            result[str(row["clip_state"])] = int(row["count"])
        terminal_failed = result["FAILED"]
        result.update({
            "PENDING": result["RECORDING"] + result["LOCAL_PENDING"],
            "UPLOADING": result["DRIVE_UPLOADING"],
            "FAILED": result["UPLOAD_RETRY"] + result["FAILED"],
            "UPLOADED": result["DRIVE_READY"] + result["LOCAL_CACHE"],
            "TERMINAL_FAILED": terminal_failed,
        })
        return result

    def uploaded_local_clips_before(self, before_ms: int, limit: int = 100) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips WHERE local_state='AVAILABLE' "
                "AND clip_state='LOCAL_CACHE' AND remote_verified_at_ms IS NOT NULL "
                "AND remote_size_bytes=size_bytes AND youtube_status='YOUTUBE_READY' "
                "AND uploaded_at_ms<=? "
                "ORDER BY uploaded_at_ms LIMIT ?",
                (before_ms, max(1, min(500, limit))),
            ).fetchall()
        return [dict(row) for row in rows]

    def local_cache_clips(self, limit: int = 100) -> list[dict]:
        """
        Trả về các clip cục bộ được phép xóa khi Gateway thiếu dung lượng.

        Chỉ xóa khi:
        - File đã upload và được xác minh trên Google Drive.
        - Kích thước file Drive khớp với file cục bộ.
        - Video YouTube đã upload và xử lý thành công.
        """
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips "
                "WHERE local_state='AVAILABLE' "
                "AND clip_state='LOCAL_CACHE' "
                "AND remote_verified_at_ms IS NOT NULL "
                "AND remote_size_bytes=size_bytes "
                "AND youtube_status='YOUTUBE_READY' "
                "ORDER BY uploaded_at_ms, started_at_ms "
                "LIMIT ?",
                (max(1, min(500, limit)),),
            ).fetchall()

        return [dict(row) for row in rows]

    def mark_local_missing(self, clip_id: str) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET local_state='MISSING', "
                "upload_state=CASE WHEN remote_verified_at_ms IS NOT NULL AND remote_size_bytes=size_bytes "
                "THEN 'UPLOADED' ELSE 'FAILED' END, "
                "local_deleted_at_ms=CAST(strftime('%s','now') AS INTEGER) * 1000, "
                "clip_state=CASE WHEN remote_verified_at_ms IS NOT NULL AND remote_size_bytes=size_bytes "
                "THEN 'DRIVE_READY' ELSE 'FAILED' END, state_updated_at_ms=CAST(strftime('%s','now') AS INTEGER) * 1000 "
                "WHERE id=?",
                (clip_id,),
            )

    def set_clip_protected(self, clip_id: str, protected: bool) -> dict | None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET protected=? WHERE id=?",
                (1 if protected else 0, clip_id),
            )
            row = connection.execute(
                "SELECT * FROM recording_clips WHERE id=?", (clip_id,)
            ).fetchone()
        return dict(row) if row else None

    def remote_clip_count(self, remote_id: str) -> int:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT COUNT(*) AS count FROM recording_clips "
                "WHERE remote_id=? AND clip_state IN ('DRIVE_READY', 'LOCAL_CACHE')",
                (remote_id,),
            ).fetchone()
        return int(row["count"])

    def recording_statistics(self) -> dict:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT COUNT(*) AS clip_count, COALESCE(SUM(size_bytes), 0) AS total_bytes, "
                "COALESCE(SUM(duration_ms), 0) AS total_duration_ms, "
                "MIN(started_at_ms) AS first_clip_ms, MAX(started_at_ms) AS last_clip_ms "
                "FROM recording_clips"
            ).fetchone()
        return dict(row)

    def record_motion_event(self, camera_id: str, detected_at_ms: int) -> None:
        with self._lock, self.connect() as connection:
            last = connection.execute(
                "SELECT MAX(detected_at_ms) AS value FROM motion_events WHERE camera_id=?",
                (camera_id,),
            ).fetchone()["value"]
            if last is None or detected_at_ms - int(last) >= 10_000:
                connection.execute(
                    "INSERT INTO motion_events(camera_id, detected_at_ms) VALUES (?, ?)",
                    (camera_id, detected_at_ms),
                )

    def apply_motion_events(self, now_ms: int, pre_ms: int = 10_000, post_ms: int = 30_000) -> int:
        changed = 0
        with self._lock, self.connect() as connection:
            events = connection.execute(
                "SELECT id, camera_id, detected_at_ms FROM motion_events WHERE processed=0"
            ).fetchall()
            for event in events:
                cursor = connection.execute(
                    "UPDATE recording_clips SET motion=1, protected=1 "
                    "WHERE camera_id=? AND started_at_ms<=? "
                    "AND started_at_ms+COALESCE(duration_ms, 60000)>=?",
                    (
                        event["camera_id"],
                        int(event["detected_at_ms"]) + post_ms,
                        int(event["detected_at_ms"]) - pre_ms,
                    ),
                )
                changed += cursor.rowcount
            connection.execute(
                "UPDATE motion_events SET processed=1 "
                "WHERE processed=0 AND detected_at_ms<?",
                (now_ms - post_ms - 120_000,),
            )
        return changed

    def remote_cleanup_candidates(
        self, remote_id: str, before_ms: int, limit: int = 20
    ) -> list[dict]:
        """
        Trả về các clip trên Google Drive được phép xóa khi Drive gần đầy.

        Chỉ xóa khi:
        - File Drive đã được xác minh.
        - Kích thước file Drive khớp với clip gốc.
        - Bản YouTube đã upload và xử lý thành công.
        - Clip không được người dùng bảo vệ.
        - Clip đã cũ hơn thời gian lưu tối thiểu.
        """
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips "
                "WHERE remote_id=? "
                "AND clip_state IN ('DRIVE_READY', 'LOCAL_CACHE') "
                "AND remote_verified_at_ms IS NOT NULL "
                "AND remote_size_bytes=size_bytes "
                "AND youtube_status='YOUTUBE_READY' "
                "AND protected=0 "
                "AND started_at_ms<=? "
                "ORDER BY started_at_ms "
                "LIMIT ?",
                (remote_id, before_ms, max(1, min(100, limit))),
            ).fetchall()

        return [dict(row) for row in rows]

    def record_deleted_clip(self, clip: dict, reason: str, deleted_at_ms: int) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "INSERT INTO deletion_history(clip_id, camera_id, relative_path, remote_id, "
                "reason, size_bytes, deleted_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (
                    clip["id"], clip["camera_id"], clip["relative_path"],
                    clip.get("remote_id"), reason, clip["size_bytes"], deleted_at_ms,
                ),
            )
            connection.execute("DELETE FROM recording_clips WHERE id=?", (clip["id"],))
