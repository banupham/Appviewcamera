import json
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


def test_active_segment_transitions_from_recording_to_local_pending(gateway_home, monkeypatch):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    manager = RecordingManager(settings, database)
    clip = manager.root / "camera01" / "active.mp4"
    clip.parent.mkdir(parents=True)
    clip.write_bytes(b"growing")
    monkeypatch.setattr(RecordingManager, "_probe_duration_ms", staticmethod(lambda _: 60_000))

    manager.scan([{"id": "camera01"}])
    indexed = database.find_clip_by_path("camera01/active.mp4")
    assert indexed["clip_state"] == "RECORDING"
    assert database.pending_clips(10**15) == []

    old = clip.stat().st_mtime - 5
    os.utime(clip, (old, old))
    manager.scan([{"id": "camera01"}])

    indexed = database.find_clip_by_path("camera01/active.mp4")
    assert indexed["clip_state"] == "LOCAL_PENDING"
    assert database.pending_clips(10**15)[0]["id"] == indexed["id"]


def test_recording_is_automatic_even_with_legacy_disabled_config(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    manager = RecordingManager(settings, GatewayDatabase(settings.database_path))

    assert manager.status()["enabled"] is True
    assert manager.update(False, 30)["enabled"] is True
    assert manager.config()["local_retention_minutes"] == 30


def test_legacy_stock_retention_migrates_to_six_hour_cache(gateway_home):
    path = gateway_home / "config" / "recording.json"
    path.write_text(json.dumps({
        "recording": {
            "enabled": False,
            "root": "recordings",
            "local_retention_minutes": 60,
            "keep_uploaded_local_hours": 12,
        }
    }), encoding="utf-8")
    manager = RecordingManager(
        GatewaySettings.load(gateway_home),
        GatewayDatabase(GatewaySettings.load(gateway_home).database_path),
    )

    assert manager.config()["local_cache_retention_minutes"] == 360


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
    healthy_usage = type(
        "Usage", (), {"total": 10 * 1024**3, "used": 2 * 1024**3, "free": 8 * 1024**3}
    )()
    monkeypatch.setattr("appviewcamera_gateway.recording.shutil.disk_usage", lambda _: healthy_usage)

    class FakeDrive:
        uploaded = []

        def upload_account(self):
            return {"id": "drive01"}

        def remote_root(self):
            return "CameraBackup"

        def upload_file(self, remote_id, local_path, remote_path):
            self.uploaded.append((remote_id, local_path, remote_path))
            return {
                "file_id": "drive-file-01",
                "size_bytes": local_path.stat().st_size,
                "verified_at_ms": 1234,
            }

        def account_upload_completed(self, remote_id, size_bytes):
            pass

        def retry_seconds(self):
            return [60]

        def retention_policy(self):
            return {"cleanup_start_percent": 90, "cleanup_target_percent": 80, "minimum_retention_days": 7}

        def list(self):
            return []

    drive = FakeDrive()
    worker = RecordingWorker(manager, database, drive, lambda: [{"id": "camera01"}])
    worker.run_once()

    saved = database.list_clips()[0]
    assert saved["upload_state"] == "UPLOADED"
    assert saved["clip_state"] == "LOCAL_CACHE"
    assert saved["remote_id"] == "drive01"
    assert saved["remote_file_id"] == "drive-file-01"
    assert saved["remote_size_bytes"] == len(b"fake-mp4")
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


def test_motion_event_marks_overlapping_clip_as_protected(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    database.upsert_clip(
        {
            "id": "motion-clip",
            "camera_id": "camera01",
            "relative_path": "camera01/motion.mp4",
            "started_at_ms": 100_000,
            "duration_ms": 60_000,
            "size_bytes": 100,
            "modified_ns": 1,
        }
    )
    database.record_motion_event("camera01", 120_000)

    database.apply_motion_events(121_000)

    clip = database.get_clip("motion-clip")
    assert clip["motion"] == 1
    assert clip["protected"] == 1


def test_drive_cleanup_never_selects_protected_clip(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    for clip_id, protected in (("normal", False), ("locked", True)):
        database.upsert_clip(
            {
                "id": clip_id,
                "camera_id": "camera01",
                "relative_path": f"camera01/{clip_id}.mp4",
                "started_at_ms": 1,
                "duration_ms": 1000,
                "size_bytes": 10,
                "modified_ns": 1,
            }
        )
        database.mark_uploading(clip_id, "drive01", f"CameraBackup/{clip_id}.mp4")
        database.mark_uploaded(clip_id, 1)
        database.set_clip_protected(clip_id, protected)

    candidates = database.remote_cleanup_candidates("drive01", 10_000)

    assert [item["id"] for item in candidates] == ["normal"]


def test_offline_upload_keeps_local_clip_for_retry(gateway_home, monkeypatch):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    manager = RecordingManager(settings, database)
    clip = manager.root / "camera01" / "2026-07-15" / "2026-07-15_08-09-10-123456.mp4"
    clip.parent.mkdir(parents=True)
    clip.write_bytes(b"must-survive-offline")
    old = clip.stat().st_mtime - 5
    os.utime(clip, (old, old))
    monkeypatch.setattr(RecordingManager, "_probe_duration_ms", staticmethod(lambda _: 60_000))

    class OfflineDrive:
        def upload_account(self): return {"id": "drive01"}
        def remote_root(self): return "CameraBackup"
        def upload_file(self, remote_id, local_path, remote_path):
            raise RuntimeError("Internet offline")
        def retry_seconds(self): return [60]
        def retention_policy(self):
            return {"cleanup_start_percent": 90, "cleanup_target_percent": 80, "minimum_retention_days": 7}
        def list(self): return []

    worker = RecordingWorker(manager, database, OfflineDrive(), lambda: [{"id": "camera01"}])
    try:
        worker.run_once()
        assert False, "offline upload must report failure"
    except RuntimeError as error:
        assert "offline" in str(error).lower()

    saved = database.list_clips()[0]
    assert saved["clip_state"] == "UPLOAD_RETRY"
    assert clip.read_bytes() == b"must-survive-offline"
    assert database.pending_clips(saved["next_retry_ms"] - 1) == []
    assert database.pending_clips(saved["next_retry_ms"])[0]["id"] == saved["id"]


def test_cleanup_never_deletes_unverified_or_retry_clip(gateway_home, monkeypatch):
    settings = GatewaySettings.load(gateway_home)
    database = GatewayDatabase(settings.database_path)
    manager = RecordingManager(settings, database)
    paths = []
    for clip_id in ("verified", "retry"):
        path = manager.root / "camera01" / f"{clip_id}.mp4"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(clip_id.encode())
        paths.append(path)
        database.upsert_clip({
            "id": clip_id, "camera_id": "camera01", "relative_path": f"camera01/{clip_id}.mp4",
            "started_at_ms": 1, "duration_ms": 1000, "size_bytes": path.stat().st_size,
            "modified_ns": path.stat().st_mtime_ns,
        })
    database.mark_uploading("verified", "drive01", "CameraBackup/verified.mp4")
    database.mark_uploaded("verified", 1, "file-verified", paths[0].stat().st_size, 1)
    database.mark_upload_failed("retry", "offline", 999999)

    class Drive:
        def retention_policy(self):
            return {"cleanup_start_percent": 90, "cleanup_target_percent": 80, "minimum_retention_days": 7}
        def list(self): return []

    usage = type("Usage", (), {"total": 1000, "used": 950, "free": 50})()
    monkeypatch.setattr("appviewcamera_gateway.recording.shutil.disk_usage", lambda _: usage)
    worker = RecordingWorker(manager, database, Drive(), lambda: [])
    worker._apply_retention()

    assert not paths[0].exists()
    assert database.get_clip("verified")["clip_state"] == "DRIVE_READY"
    assert paths[1].exists()
    assert database.get_clip("retry")["clip_state"] == "UPLOAD_RETRY"
