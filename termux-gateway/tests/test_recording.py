import os

from appviewcamera_gateway.config import GatewaySettings
from appviewcamera_gateway.database import GatewayDatabase
from appviewcamera_gateway.recording import RecordingManager


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
