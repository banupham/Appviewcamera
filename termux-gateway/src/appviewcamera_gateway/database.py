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
    modified_ns INTEGER NOT NULL
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
                    modified_ns=excluded.modified_ns
                """,
                clip,
            )

    def delete_missing_clips(self, relative_paths: set[str]) -> None:
        with self._lock, self.connect() as connection:
            indexed = {
                str(row["relative_path"])
                for row in connection.execute("SELECT relative_path FROM recording_clips").fetchall()
            }
            missing = indexed - relative_paths
            connection.executemany(
                "DELETE FROM recording_clips WHERE relative_path=?",
                ((path,) for path in missing),
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
                "SELECT id, camera_id, started_at_ms, duration_ms, size_bytes "
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
