import os

from appviewcamera_gateway.config import GatewaySettings
from appviewcamera_gateway.database import GatewayDatabase
from appviewcamera_gateway.recording import RecordingManager, RecordingWorker


def test_recording_scan_indexes_completed_mp4(gateway_home, monkeypatch):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    manager = RecordingManager(settings, database)
    clip = manager.root / "camera01" / "2026-07-15" / "2026-07-15_08-09-10-123456.mp4"
    clip.parent.mkdir(parents=True)
    clip.write_bytes(b"fake-mp4")
    old = clip.stat().st_mtime - 5
    os.utime(clip, (old, old))
    monkeypatch.setattr(RecordingManager, "_probe_duration_ms", staticmethod(lambda _: 60_000))

    manager.scan([{"id": "camera01"}])
    clips = database.list_clips("camera01")

    assert len(clips) == 1
    assert clips[0]["duration_ms"] == 60_000
    assert manager.clip_path(clips[0]["id"]) == clip


def test_recording_is_disabled_by_default(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    manager = RecordingManager(settings, GatewayDatabase(settings.database_path))

    assert manager.status()["enabled"] is False
    assert manager.update(True, 30)["enabled"] is True
    assert manager.config()["local_retention_minutes"] == 30


def test_uploaded_clip_metadata_survives_local_cleanup(gateway_home, monkeypatch):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    manager = RecordingManager(settings, database)
    clip = manager.root / "camera01" / "2026-07-15" / "2026-07-15_08-09-10-123456.mp4"
    clip.parent.mkdir(parents=True)
    clip.write_bytes(b"fake-mp4")
    old = clip.stat().st_mtime - 5
    os.utime(clip, (old, old))
    monkeypatch.setattr(RecordingManager, "_probe_duration_ms", staticmethod(lambda _: 60_000))
    manager.scan([{"id": "camera01"}])
    indexed = database.list_clips()[0]
    database.mark_uploading(indexed["id"], "drive01", "CameraBackup/camera01/clip.mp4")
    database.mark_uploaded(indexed["id"], 1)

    clip.unlink()
    manager.scan([{"id": "camera01"}])

    saved = database.get_clip(indexed["id"])
    assert saved is not None
    assert saved["local_state"] == "MISSING"
    assert saved["upload_state"] == "UPLOADED"


def test_worker_uploads_pending_clip_and_marks_verified(gateway_home, monkeypatch):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    manager = RecordingManager(settings, database)
    clip = manager.root / "camera01" / "2026-07-15" / "2026-07-15_08-09-10-123456.mp4"
    clip.parent.mkdir(parents=True)
    clip.write_bytes(b"fake-mp4")
    old = clip.stat().st_mtime - 5
    os.utime(clip, (old, old))
    monkeypatch.setattr(RecordingManager, "_probe_duration_ms", staticmethod(lambda _: 60_000))

    class FakeDrive:
        uploaded = []

        def active_account(self):
            return {"id": "drive01"}

        def remote_root(self):
            return "CameraBackup"

        def upload_file(self, remote_id, local_path, remote_path):
            self.uploaded.append((remote_id, local_path, remote_path))

        def retry_seconds(self):
            return [60]

    drive = FakeDrive()
    worker = RecordingWorker(manager, database, drive, lambda: [{"id": "camera01"}])
    worker.run_once()

    saved = database.list_clips()[0]
    assert saved["upload_state"] == "UPLOADED"
    assert saved["remote_id"] == "drive01"
    assert drive.uploaded[0][2].startswith("CameraBackup/camera01/")


def test_playback_downloads_uploaded_clip_when_local_file_is_gone(gateway_home, monkeypatch):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    manager = RecordingManager(settings, database)
    clip = manager.root / "camera01" / "2026-07-15" / "2026-07-15_08-09-10-123456.mp4"
    clip.parent.mkdir(parents=True)
    clip.write_bytes(b"fake-mp4")
    old = clip.stat().st_mtime - 5
    os.utime(clip, (old, old))
    monkeypatch.setattr(RecordingManager, "_probe_duration_ms", staticmethod(lambda _: 60_000))
    manager.scan([{"id": "camera01"}])
    indexed = database.list_clips()[0]
    database.mark_uploading(indexed["id"], "drive01", "CameraBackup/camera01/clip.mp4")
    database.mark_uploaded(indexed["id"], 1)
    clip.unlink()
    manager.scan([{"id": "camera01"}])

    class FakeDrive:
        def download_file(self, remote_id, remote_path, local_path):
            assert remote_id == "drive01"
            assert remote_path == "CameraBackup/camera01/clip.mp4"
            local_path.parent.mkdir(parents=True, exist_ok=True)
            local_path.write_bytes(b"downloaded-mp4")

    playback = manager.playback_path(indexed["id"], FakeDrive())

    assert playback is not None
    assert playback.read_bytes() == b"downloaded-mp4"
