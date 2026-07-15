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
    local_state TEXT NOT NULL DEFAULT 'AVAILABLE',
    upload_state TEXT NOT NULL DEFAULT 'PENDING',
    remote_id TEXT,
    remote_path TEXT,
    upload_attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_ms INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    uploaded_at_ms INTEGER,
    protected INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_recording_camera_time
ON recording_clips(camera_id, started_at_ms DESC);
"""


class GatewayDatabase:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        with self.connect() as connection:
            connection.executescript(SCHEMA)
            self._migrate_recording_clips(connection)

    @staticmethod
    def _migrate_recording_clips(connection: sqlite3.Connection) -> None:
        columns = {
            str(row[1])
            for row in connection.execute("PRAGMA table_info(recording_clips)").fetchall()
        }
        migrations = {
            "local_state": "TEXT NOT NULL DEFAULT 'AVAILABLE'",
            "upload_state": "TEXT NOT NULL DEFAULT 'PENDING'",
            "remote_id": "TEXT",
            "remote_path": "TEXT",
            "upload_attempts": "INTEGER NOT NULL DEFAULT 0",
            "next_retry_ms": "INTEGER NOT NULL DEFAULT 0",
            "last_error": "TEXT",
            "uploaded_at_ms": "INTEGER",
            "protected": "INTEGER NOT NULL DEFAULT 0",
        }
        for name, declaration in migrations.items():
            if name not in columns:
                connection.execute(
                    f"ALTER TABLE recording_clips ADD COLUMN {name} {declaration}"
                )

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        return connection

    def save_candidates(self, candidates: Iterable[dict]) -> None:
        with self._lock, self.connect() as connection:
            connection.executemany(
                """
                INSERT INTO discovery_candidates(host, port, source, service_url)
                VALUES (:host, :port, :source, :service_url)
                ON CONFLICT(host, port) DO UPDATE SET
                    source=excluded.source,
                    service_url=COALESCE(excluded.service_url, discovery_candidates.service_url),
                    last_seen=CURRENT_TIMESTAMP
                """,
                candidates,
            )

    def list_candidates(self) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT host, port, source, service_url, first_seen, last_seen "
                "FROM discovery_candidates ORDER BY last_seen DESC, host, port"
            ).fetchall()
        return [dict(row) for row in rows]

    def find_clip_by_path(self, relative_path: str) -> dict | None:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT * FROM recording_clips WHERE relative_path=?", (relative_path,)
            ).fetchone()
        return dict(row) if row else None

    def upsert_clip(self, clip: dict) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                """
                INSERT INTO recording_clips(
                    id, camera_id, relative_path, started_at_ms,
                    duration_ms, size_bytes, modified_ns
                ) VALUES (
                    :id, :camera_id, :relative_path, :started_at_ms,
                    :duration_ms, :size_bytes, :modified_ns
                )
                ON CONFLICT(relative_path) DO UPDATE SET
                    camera_id=excluded.camera_id,
                    started_at_ms=excluded.started_at_ms,
                    duration_ms=excluded.duration_ms,
                    size_bytes=excluded.size_bytes,
                    modified_ns=excluded.modified_ns,
                    local_state='AVAILABLE'
                """,
                clip,
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
                    "UPDATE recording_clips SET local_state='MISSING' WHERE relative_path=?",
                    ((path,) for path in missing),
                )
                connection.execute(
                    "DELETE FROM recording_clips "
                    "WHERE local_state='MISSING' AND upload_state!='UPLOADED'"
                )

    def list_clips(
        self,
        camera_id: str | None = None,
        from_ms: int | None = None,
        to_ms: int | None = None,
        limit: int = 200,
    ) -> list[dict]:
        clauses: list[str] = []
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
                "local_state, upload_state, remote_id, remote_path, last_error, protected "
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

    def clip_count(self) -> int:
        with self.connect() as connection:
            row = connection.execute("SELECT COUNT(*) AS count FROM recording_clips").fetchone()
        return int(row["count"])

    def pending_clips(self, now_ms: int, limit: int = 10) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips "
                "WHERE local_state='AVAILABLE' "
                "AND upload_state IN ('PENDING', 'FAILED') AND next_retry_ms<=? "
                "ORDER BY started_at_ms LIMIT ?",
                (now_ms, max(1, min(100, limit))),
            ).fetchall()
        return [dict(row) for row in rows]

    def mark_uploading(self, clip_id: str, remote_id: str, remote_path: str) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET upload_state='UPLOADING', remote_id=?, "
                "remote_path=?, upload_attempts=upload_attempts+1, last_error=NULL "
                "WHERE id=?",
                (remote_id, remote_path, clip_id),
            )

    def mark_uploaded(self, clip_id: str, uploaded_at_ms: int) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET upload_state='UPLOADED', uploaded_at_ms=?, "
                "next_retry_ms=0, last_error=NULL WHERE id=?",
                (uploaded_at_ms, clip_id),
            )

    def mark_upload_failed(self, clip_id: str, error: str, next_retry_ms: int) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET upload_state='FAILED', last_error=?, "
                "next_retry_ms=? WHERE id=?",
                (error[-500:], next_retry_ms, clip_id),
            )

    def reset_interrupted_uploads(self) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET upload_state='FAILED', next_retry_ms=0, "
                "last_error='Gateway restarted during upload' WHERE upload_state='UPLOADING'"
            )

    def upload_counts(self) -> dict[str, int]:
        result = {"PENDING": 0, "UPLOADING": 0, "FAILED": 0, "UPLOADED": 0}
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT upload_state, COUNT(*) AS count FROM recording_clips GROUP BY upload_state"
            ).fetchall()
        for row in rows:
            result[str(row["upload_state"])] = int(row["count"])
        return result

    def uploaded_local_clips_before(self, before_ms: int, limit: int = 100) -> list[dict]:
        with self.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips WHERE local_state='AVAILABLE' "
                "AND upload_state='UPLOADED' AND protected=0 AND uploaded_at_ms<=? "
                "ORDER BY uploaded_at_ms LIMIT ?",
                (before_ms, max(1, min(500, limit))),
            ).fetchall()
        return [dict(row) for row in rows]

    def mark_local_missing(self, clip_id: str) -> None:
        with self._lock, self.connect() as connection:
            connection.execute(
                "UPDATE recording_clips SET local_state='MISSING' WHERE id=?",
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
                "WHERE remote_id=? AND upload_state='UPLOADED'",
                (remote_id,),
            ).fetchone()
        return int(row["count"])
