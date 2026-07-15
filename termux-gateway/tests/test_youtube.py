import json
from pathlib import Path

from appviewcamera_gateway.config import GatewaySettings
from appviewcamera_gateway.database import GatewayDatabase
from appviewcamera_gateway.youtube.batch import (
    YouTubeBatchBuilder,
    estimated_uploads_per_day,
    quota_aware_target_minutes,
)
from appviewcamera_gateway.youtube.index import YouTubeRepository
from appviewcamera_gateway.youtube.uploader import YouTubeResumableUploader
from appviewcamera_gateway.youtube.worker import YouTubeArchiveWorker


def youtube_config(gateway_home, **overrides):
    values = {
        "enabled": True,
        "target_youtube_duration_minutes": 60,
        "max_target_uploads_per_day": 80,
        "upload_limit_per_day": 100,
        "batch_root": "youtube/batches",
    }
    values.update(overrides)
    (gateway_home / "config" / "youtube.json").write_text(
        json.dumps({"youtube": values}), encoding="utf-8"
    )


def insert_drive_clip(database, clip_id, camera_id, start_ms, duration_ms=30 * 60_000):
    with database.connect() as connection:
        connection.execute(
            """INSERT INTO recording_clips(
                id,camera_id,relative_path,started_at_ms,duration_ms,size_bytes,modified_ns,
                clip_state,local_state,upload_state,remote_id,remote_path,remote_file_id,
                remote_size_bytes,remote_verified_at_ms,state_updated_at_ms
            ) VALUES(?,?,?,?,?,100,1,'LOCAL_CACHE','AVAILABLE','UPLOADED','drive01',?,?,100,?,?)""",
            (
                clip_id, camera_id, f"{camera_id}/{clip_id}.mp4", start_ms, duration_ms,
                f"archive/{clip_id}.mp4", f"file-{clip_id}", start_ms, start_ms,
            ),
        )


def test_quota_calculation_increases_four_cameras_to_safe_duration():
    assert estimated_uploads_per_day(2, 30) == 96
    policy = quota_aware_target_minutes(4, 60, 80, 100)
    assert policy["target_minutes"] == 90
    assert policy["estimated_uploads_per_day"] == 64
    assert policy["warning"]


def test_batch_groups_same_camera_and_day_only(gateway_home, tmp_path):
    youtube_config(gateway_home)
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    repository = YouTubeRepository(database)
    day = 1_768_435_200_000
    insert_drive_clip(database, "a1", "camera01", day)
    insert_drive_clip(database, "a2", "camera01", day + 30 * 60_000)
    insert_drive_clip(database, "b1", "camera02", day)
    insert_drive_clip(database, "b2", "camera02", day + 30 * 60_000)
    insert_drive_clip(database, "tomorrow", "camera01", day + 24 * 60 * 60_000)

    class Recording:
        def playback_path(self, clip_id, drive, source):
            return tmp_path / f"{clip_id}.mp4"

    builder = YouTubeBatchBuilder(
        repository, Recording(), object(), "gateway-test", lambda: __import__(
            "appviewcamera_gateway.youtube.account", fromlist=["YouTubeConfig"]
        ).YouTubeConfig.load(gateway_home), lambda: [{"id": "camera01"}, {"id": "camera02"}],
    )
    builder._stream_copy = lambda batch_id, clips, root: _fake_batch_file(tmp_path, batch_id)
    job = builder.build_next()
    clips = repository.batch_clips(job["batch_id"])
    assert {clip["camera_id"] for clip in clips} == {"camera01"}
    assert {clip["id"] for clip in clips} == {"a1", "a2"}
    repository.mark_uploaded(job["id"], "youtube-video-01")
    mapped = repository.batch_clips(job["batch_id"])
    assert [clip["youtube_start_offset_seconds"] for clip in mapped] == [0, 1800]
    assert [clip["youtube_end_offset_seconds"] for clip in mapped] == [1800, 3600]


def _fake_batch_file(root: Path, batch_id: str) -> Path:
    path = root / f"{batch_id}.mp4"
    path.write_bytes(b"batch")
    return path


def test_resumable_retry_reuses_saved_session_and_does_not_create_duplicate(tmp_path):
    video = tmp_path / "batch.mp4"
    video.write_bytes(b"abcdefgh")

    class Repository:
        started = 0
        progress = []
        uploaded = []
        def mark_uploading(self, job_id, account_id): pass
        def save_upload_progress(self, job_id, value): self.progress.append(value)
        def save_upload_session(self, job_id, url): self.started += 1
        def clear_upload_session(self, job_id): pass
        def mark_uploaded(self, job_id, video_id): self.uploaded.append(video_id)

    class Accounts:
        def access_token(self, account_id=None): return "account01", "access-secret"
        def config(self): return type("Config", (), {"chunk_bytes": 256 * 1024})()

    requests = []
    responses = [
        FakeResponse(308, {"Range": "bytes=0-3"}, b""),
        FakeResponse(200, {}, b'{"id":"video-private-01"}'),
    ]
    def requester(request, timeout):
        requests.append(request)
        return responses.pop(0)

    repository = Repository()
    uploader = YouTubeResumableUploader(repository, Accounts(), requester)
    result = uploader.upload({
        "id": "job01", "batch_id": "batch01", "local_path": str(video),
        "title": "CAM01_2026-07-15_08-00_to_09-00", "description": "Gateway ID: x",
        "upload_url": "https://upload.example/session-1", "uploaded_bytes": 0,
        "account_id": "account01",
    })
    assert result == "video-private-01"
    assert repository.started == 0
    assert all(request.full_url == "https://upload.example/session-1" for request in requests)
    assert requests[1].headers["Content-range"] == "bytes 4-7/8"


class FakeResponse:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self): return self.body


def test_youtube_repository_never_exposes_oauth_tokens(gateway_home):
    database = GatewayDatabase(GatewaySettings.load(gateway_home).database_path)
    repository = YouTubeRepository(database)
    repository.upsert_account(
        "youtube01", "Private archive",
        {"access_token": "secret-access", "refresh_token": "secret-refresh"},
        "https://www.googleapis.com/auth/youtube.upload",
    )
    public = repository.list_accounts()
    assert "secret-access" not in str(public)
    assert "secret-refresh" not in str(public)
    assert public[0]["id"] == "youtube01"


def test_youtube_failure_is_contained_and_does_not_raise_into_drive_worker():
    class Repository:
        retried = []
        def reset_interrupted(self): pass
        def next_upload_job(self, now): return {"id": "job01", "attempts": 0}
        def mark_retry(self, job_id, error, next_retry_ms): self.retried.append((job_id, error))
        def processing_job(self): return None
        def status_counts(self): return {}

    class Accounts:
        def config(self):
            return type("Config", (), {"enabled": True, "retry_seconds": (60,)})()
        def list(self): return [{"id": "youtube01"}]

    class Builder:
        def build_next(self): return None
        def policy(self):
            return {"target_minutes": 60, "estimated_uploads_per_day": 24, "safe_upload_limit": 80, "warning": None}

    class BrokenUploader:
        def upload(self, job): raise OSError("YouTube offline")

    repository = Repository()
    worker = YouTubeArchiveWorker(
        repository, Accounts(), Builder(), BrokenUploader(), object()
    )
    worker.run_once()
    assert repository.retried == [("job01", "YouTube offline")]
    assert worker.last_error == "YouTube offline"
