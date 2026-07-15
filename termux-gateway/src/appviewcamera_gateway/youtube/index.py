from __future__ import annotations

import json
import time
import uuid
from typing import Any

from ..database import GatewayDatabase


class YouTubeRepository:
    def __init__(self, database: GatewayDatabase):
        self.database = database

    def upsert_account(
        self, account_id: str, display_name: str, token: dict[str, Any], scope: str
    ) -> dict[str, Any]:
        now = int(time.time() * 1000)
        with self.database._lock, self.database.connect() as connection:
            count = int(connection.execute("SELECT COUNT(*) FROM youtube_accounts").fetchone()[0])
            connection.execute(
                """INSERT INTO youtube_accounts(
                    id,display_name,token_json,scope,active,status,last_error,created_at_ms,updated_at_ms
                ) VALUES(?,?,?,?,?,'ONLINE',NULL,?,?)
                ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,
                    token_json=excluded.token_json,scope=excluded.scope,status='ONLINE',
                    last_error=NULL,updated_at_ms=excluded.updated_at_ms""",
                (
                    account_id,
                    display_name,
                    json.dumps(token, separators=(",", ":")),
                    scope,
                    1 if count == 0 else 0,
                    now,
                    now,
                ),
            )
        return self.public_account(account_id) or {}

    def list_accounts(self) -> list[dict[str, Any]]:
        with self.database.connect() as connection:
            rows = connection.execute(
                "SELECT id,display_name,active,status,last_error,updated_at_ms "
                "FROM youtube_accounts ORDER BY created_at_ms"
            ).fetchall()
        return [dict(row) for row in rows]

    def public_account(self, account_id: str) -> dict[str, Any] | None:
        return next((item for item in self.list_accounts() if item["id"] == account_id), None)

    def account(self, account_id: str | None = None) -> dict[str, Any] | None:
        with self.database.connect() as connection:
            if account_id:
                row = connection.execute(
                    "SELECT * FROM youtube_accounts WHERE id=?", (account_id,)
                ).fetchone()
            else:
                row = connection.execute(
                    "SELECT * FROM youtube_accounts ORDER BY active DESC,created_at_ms LIMIT 1"
                ).fetchone()
        if row is None:
            return None
        result = dict(row)
        result["token"] = json.loads(str(result.pop("token_json")))
        return result

    def update_account_token(self, account_id: str, token: dict[str, Any]) -> None:
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_accounts SET token_json=?,status='ONLINE',last_error=NULL,updated_at_ms=? WHERE id=?",
                (json.dumps(token, separators=(",", ":")), int(time.time() * 1000), account_id),
            )

    def account_error(self, account_id: str, error: str) -> None:
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_accounts SET status='ERROR',last_error=?,updated_at_ms=? WHERE id=?",
                (error[-500:], int(time.time() * 1000), account_id),
            )

    def delete_account(self, account_id: str) -> bool:
        with self.database._lock, self.database.connect() as connection:
            existing = connection.execute(
                "SELECT active FROM youtube_accounts WHERE id=?", (account_id,)
            ).fetchone()
            if existing is None:
                return False
            connection.execute("DELETE FROM youtube_accounts WHERE id=?", (account_id,))
            connection.execute(
                "UPDATE youtube_upload_jobs SET account_id=NULL WHERE account_id=?", (account_id,)
            )
            if bool(existing["active"]):
                first = connection.execute(
                    "SELECT id FROM youtube_accounts ORDER BY created_at_ms LIMIT 1"
                ).fetchone()
                if first:
                    connection.execute(
                        "UPDATE youtube_accounts SET active=1 WHERE id=?", (first["id"],)
                    )
        return True

    def eligible_clips(self, limit: int = 2000) -> list[dict[str, Any]]:
        with self.database.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips WHERE youtube_batch_id IS NULL "
                "AND clip_state IN ('DRIVE_READY','LOCAL_CACHE') "
                "AND upload_state='UPLOADED' AND remote_verified_at_ms IS NOT NULL "
                "AND duration_ms>0 ORDER BY camera_id,started_at_ms LIMIT ?",
                (max(1, min(5000, limit)),),
            ).fetchall()
        return [dict(row) for row in rows]

    def create_batch(self, batch: dict[str, Any], clip_ids: list[str]) -> dict[str, Any]:
        now = int(time.time() * 1000)
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                """INSERT INTO youtube_batches(
                    id,camera_id,local_day,title,description,start_time_ms,end_time_ms,duration_ms,
                    target_duration_minutes,state,created_at_ms,updated_at_ms
                ) VALUES(:id,:camera_id,:local_day,:title,:description,:start_time_ms,:end_time_ms,
                    :duration_ms,:target_duration_minutes,'YOUTUBE_BATCHING',:now,:now)""",
                {**batch, "now": now},
            )
            placeholders = ",".join("?" for _ in clip_ids)
            cursor = connection.execute(
                f"UPDATE recording_clips SET youtube_batch_id=?,youtube_status='YOUTUBE_BATCHING',"
                f"youtube_state='YOUTUBE_BATCHING' WHERE id IN ({placeholders}) AND youtube_batch_id IS NULL",
                (batch["id"], *clip_ids),
            )
            if cursor.rowcount != len(clip_ids):
                raise RuntimeError("Some clips were already assigned to another YouTube batch")
        return self.batch(str(batch["id"])) or {}

    def batch(self, batch_id: str) -> dict[str, Any] | None:
        with self.database.connect() as connection:
            row = connection.execute("SELECT * FROM youtube_batches WHERE id=?", (batch_id,)).fetchone()
        return dict(row) if row else None

    def batch_clips(self, batch_id: str) -> list[dict[str, Any]]:
        with self.database.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM recording_clips WHERE youtube_batch_id=? ORDER BY started_at_ms",
                (batch_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def mark_batch_file(self, batch_id: str, path: str, total_bytes: int) -> dict[str, Any]:
        now = int(time.time() * 1000)
        job_id = uuid.uuid4().hex
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_batches SET local_path=?,state='YOUTUBE_PENDING',last_error=NULL,updated_at_ms=? WHERE id=?",
                (path, now, batch_id),
            )
            connection.execute(
                """INSERT INTO youtube_upload_jobs(
                    id,batch_id,state,total_bytes,created_at_ms,updated_at_ms
                ) VALUES(?,?,'YOUTUBE_PENDING',?,?,?)
                ON CONFLICT(batch_id) DO UPDATE SET total_bytes=excluded.total_bytes,
                    state=CASE WHEN youtube_upload_jobs.youtube_video_id IS NULL
                        THEN 'YOUTUBE_PENDING' ELSE youtube_upload_jobs.state END,
                    updated_at_ms=excluded.updated_at_ms""",
                (job_id, batch_id, total_bytes, now, now),
            )
            connection.execute(
                "UPDATE recording_clips SET youtube_status='YOUTUBE_PENDING',youtube_state='YOUTUBE_PENDING' WHERE youtube_batch_id=?",
                (batch_id,),
            )
        return self.job_for_batch(batch_id) or {}

    def job_for_batch(self, batch_id: str) -> dict[str, Any] | None:
        with self.database.connect() as connection:
            row = connection.execute(
                "SELECT * FROM youtube_upload_jobs WHERE batch_id=?", (batch_id,)
            ).fetchone()
        return dict(row) if row else None

    def next_upload_job(self, now_ms: int) -> dict[str, Any] | None:
        with self.database.connect() as connection:
            row = connection.execute(
                "SELECT j.*,b.local_path,b.title,b.description,b.camera_id,b.start_time_ms,b.end_time_ms "
                "FROM youtube_upload_jobs j JOIN youtube_batches b ON b.id=j.batch_id "
                "WHERE j.state IN ('YOUTUBE_PENDING','YOUTUBE_RETRY') AND j.next_retry_ms<=? "
                "ORDER BY j.created_at_ms LIMIT 1",
                (now_ms,),
            ).fetchone()
        return dict(row) if row else None

    def mark_uploading(self, job_id: str, account_id: str) -> None:
        now = int(time.time() * 1000)
        with self.database._lock, self.database.connect() as connection:
            job = connection.execute(
                "SELECT batch_id FROM youtube_upload_jobs WHERE id=?", (job_id,)
            ).fetchone()
            if not job:
                return
            connection.execute(
                "UPDATE youtube_upload_jobs SET account_id=?,state='YOUTUBE_UPLOADING',attempts=attempts+1,updated_at_ms=? WHERE id=?",
                (account_id, now, job_id),
            )
            connection.execute(
                "UPDATE youtube_batches SET account_id=?,state='YOUTUBE_UPLOADING',updated_at_ms=? WHERE id=?",
                (account_id, now, job["batch_id"]),
            )
            connection.execute(
                "UPDATE recording_clips SET youtube_status='YOUTUBE_UPLOADING',youtube_state='YOUTUBE_UPLOADING' WHERE youtube_batch_id=?",
                (job["batch_id"],),
            )

    def save_upload_session(self, job_id: str, upload_url: str) -> None:
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_upload_jobs SET upload_url=?,updated_at_ms=? WHERE id=?",
                (upload_url, int(time.time() * 1000), job_id),
            )

    def save_upload_progress(self, job_id: str, uploaded_bytes: int) -> None:
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_upload_jobs SET uploaded_bytes=?,updated_at_ms=? WHERE id=?",
                (max(0, uploaded_bytes), int(time.time() * 1000), job_id),
            )

    def clear_upload_session(self, job_id: str) -> None:
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_upload_jobs SET upload_url=NULL,uploaded_bytes=0,updated_at_ms=? WHERE id=?",
                (int(time.time() * 1000), job_id),
            )

    def mark_uploaded(self, job_id: str, video_id: str) -> None:
        now = int(time.time() * 1000)
        with self.database._lock, self.database.connect() as connection:
            job = connection.execute(
                "SELECT batch_id FROM youtube_upload_jobs WHERE id=?", (job_id,)
            ).fetchone()
            if not job:
                return
            batch_id = str(job["batch_id"])
            connection.execute(
                "UPDATE youtube_upload_jobs SET state='YOUTUBE_PROCESSING',youtube_video_id=?,uploaded_bytes=total_bytes,last_error=NULL,updated_at_ms=? WHERE id=?",
                (video_id, now, job_id),
            )
            connection.execute(
                "UPDATE youtube_batches SET state='YOUTUBE_PROCESSING',youtube_video_id=?,updated_at_ms=? WHERE id=?",
                (video_id, now, batch_id),
            )
            offset = 0
            clips = connection.execute(
                "SELECT id,duration_ms FROM recording_clips WHERE youtube_batch_id=? ORDER BY started_at_ms",
                (batch_id,),
            ).fetchall()
            for clip in clips:
                duration_seconds = max(0, int((int(clip["duration_ms"] or 0) + 999) / 1000))
                connection.execute(
                    "UPDATE recording_clips SET youtube_video_id=?,youtube_status='YOUTUBE_PROCESSING',"
                    "youtube_state='YOUTUBE_PROCESSING',youtube_start_offset_seconds=?,"
                    "youtube_end_offset_seconds=?,youtube_uploaded_at=?,youtube_processing_status='processing' WHERE id=?",
                    (video_id, offset, offset + duration_seconds, now, clip["id"]),
                )
                offset += duration_seconds

    def processing_job(self) -> dict[str, Any] | None:
        with self.database.connect() as connection:
            row = connection.execute(
                "SELECT j.*,b.local_path FROM youtube_upload_jobs j JOIN youtube_batches b ON b.id=j.batch_id "
                "WHERE j.state='YOUTUBE_PROCESSING' ORDER BY j.updated_at_ms LIMIT 1"
            ).fetchone()
        return dict(row) if row else None

    def mark_ready(self, job_id: str, processing_status: str = "processed") -> None:
        now = int(time.time() * 1000)
        with self.database._lock, self.database.connect() as connection:
            job = connection.execute(
                "SELECT batch_id FROM youtube_upload_jobs WHERE id=?", (job_id,)
            ).fetchone()
            if not job:
                return
            batch_id = str(job["batch_id"])
            connection.execute(
                "UPDATE youtube_upload_jobs SET state='YOUTUBE_READY',last_error=NULL,updated_at_ms=? WHERE id=?",
                (now, job_id),
            )
            connection.execute(
                "UPDATE youtube_batches SET state='YOUTUBE_READY',last_error=NULL,updated_at_ms=? WHERE id=?",
                (now, batch_id),
            )
            connection.execute(
                "UPDATE recording_clips SET youtube_status='YOUTUBE_READY',youtube_state='YOUTUBE_READY',"
                "youtube_processing_status=?,youtube_updated_at_ms=? WHERE youtube_batch_id=?",
                (processing_status, now, batch_id),
            )

    def mark_retry(self, job_id: str, error: str, next_retry_ms: int) -> None:
        now = int(time.time() * 1000)
        with self.database._lock, self.database.connect() as connection:
            job = connection.execute(
                "SELECT batch_id FROM youtube_upload_jobs WHERE id=?", (job_id,)
            ).fetchone()
            if not job:
                return
            connection.execute(
                "UPDATE youtube_upload_jobs SET state='YOUTUBE_RETRY',last_error=?,next_retry_ms=?,updated_at_ms=? WHERE id=?",
                (error[-500:], next_retry_ms, now, job_id),
            )
            connection.execute(
                "UPDATE youtube_batches SET state='YOUTUBE_RETRY',last_error=?,updated_at_ms=? WHERE id=?",
                (error[-500:], now, job["batch_id"]),
            )
            connection.execute(
                "UPDATE recording_clips SET youtube_status='YOUTUBE_RETRY',youtube_state='YOUTUBE_RETRY',youtube_last_error=? WHERE youtube_batch_id=?",
                (error[-500:], job["batch_id"]),
            )

    def mark_failed(self, batch_id: str, error: str) -> None:
        now = int(time.time() * 1000)
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_batches SET state='YOUTUBE_FAILED',last_error=?,updated_at_ms=? WHERE id=?",
                (error[-500:], now, batch_id),
            )
            connection.execute(
                "UPDATE youtube_upload_jobs SET state='YOUTUBE_FAILED',last_error=?,updated_at_ms=? WHERE batch_id=?",
                (error[-500:], now, batch_id),
            )
            connection.execute(
                "UPDATE recording_clips SET youtube_status='YOUTUBE_FAILED',youtube_state='YOUTUBE_FAILED',youtube_last_error=? WHERE youtube_batch_id=?",
                (error[-500:], batch_id),
            )

    def reset_interrupted(self) -> None:
        with self.database._lock, self.database.connect() as connection:
            connection.execute(
                "UPDATE youtube_upload_jobs SET state='YOUTUBE_RETRY',next_retry_ms=0,last_error='Gateway restarted during YouTube upload' WHERE state='YOUTUBE_UPLOADING'"
            )
            connection.execute(
                "UPDATE youtube_batches SET state='YOUTUBE_RETRY' WHERE state='YOUTUBE_UPLOADING'"
            )
            connection.execute(
                "UPDATE recording_clips SET youtube_status='YOUTUBE_RETRY',youtube_state='YOUTUBE_RETRY' WHERE youtube_status='YOUTUBE_UPLOADING'"
            )

    def status_counts(self) -> dict[str, int]:
        with self.database.connect() as connection:
            rows = connection.execute(
                "SELECT state,COUNT(*) count FROM youtube_upload_jobs GROUP BY state"
            ).fetchall()
        return {str(row["state"]): int(row["count"]) for row in rows}

    def uploads_for_day(self, local_day: str) -> int:
        with self.database.connect() as connection:
            row = connection.execute(
                "SELECT COUNT(*) count FROM youtube_batches WHERE local_day=? "
                "AND state!='YOUTUBE_FAILED'",
                (local_day,),
            ).fetchone()
        return int(row["count"])

    def waiting_batch_minutes(self) -> int:
        with self.database.connect() as connection:
            row = connection.execute(
                "SELECT COALESCE(MAX(total_ms),0) value FROM ("
                "SELECT SUM(duration_ms) total_ms FROM recording_clips "
                "WHERE youtube_batch_id IS NULL AND upload_state='UPLOADED' "
                "AND remote_verified_at_ms IS NOT NULL GROUP BY camera_id, "
                "date(started_at_ms / 1000, 'unixepoch', 'localtime'))"
            ).fetchone()
        return int(int(row["value"] or 0) / 60_000)
