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
