import json
from pathlib import Path
from subprocess import CompletedProcess

from appviewcamera_gateway.storage import GoogleDriveStore


TOKEN = json.dumps(
    {
        "access_token": "access-secret",
        "token_type": "Bearer",
        "refresh_token": "refresh-secret",
        "expiry": "2030-01-01T00:00:00Z",
    }
)


def test_drive_token_is_written_but_never_returned(gateway_home):
    store = GoogleDriveStore(gateway_home)
    created = store.add("drive01", "Drive camera", TOKEN)

    assert created["configured"] is True
    assert "access-secret" not in str(created)
    assert "refresh-secret" not in str(store.list())
    assert "access-secret" in (gateway_home / "config" / "rclone.conf").read_text(encoding="utf-8")


def test_refresh_reads_quota_without_exposing_credentials(gateway_home):
    def runner(*args, **kwargs):
        return CompletedProcess(args[0], 0, '{"total":1000,"used":400,"free":600}', "")

    store = GoogleDriveStore(gateway_home, runner=runner)
    store.add("drive01", "Drive camera", TOKEN)
    refreshed = store.refresh("drive01")

    assert refreshed["status"] == "ONLINE"
    assert refreshed["quota"] == {"total": 1000, "used": 400, "free": 600}


def test_delete_removes_metadata_and_rclone_section(gateway_home):
    store = GoogleDriveStore(gateway_home)
    store.add("drive01", "Drive camera", TOKEN)

    assert store.delete("drive01") is True
    assert store.list() == []
    assert "drive01" not in (gateway_home / "config" / "rclone.conf").read_text(encoding="utf-8")


def test_upload_uses_copyto_and_verifies_remote_size(gateway_home):
    calls = []

    def runner(args, **kwargs):
        calls.append(args)
        if "lsjson" in args:
            return CompletedProcess(args, 0, '{"Size":8,"IsDir":false}', "")
        return CompletedProcess(args, 0, "", "")

    store = GoogleDriveStore(gateway_home, runner=runner)
    store.add("drive01", "Drive camera", TOKEN)
    clip = gateway_home / "clip.mp4"
    clip.write_bytes(b"fake-mp4")

    store.upload_file("drive01", clip, "CameraBackup/camera01/clip.mp4")

    assert "copyto" in calls[0]
    assert str(clip) in calls[0]
    assert "drive01:CameraBackup/camera01/clip.mp4" in calls[0]
    assert "lsjson" in calls[1]


def test_remote_path_cannot_escape_drive_root(gateway_home):
    store = GoogleDriveStore(gateway_home)
    store.add("drive01", "Drive camera", TOKEN)

    try:
        store.remote_stat("drive01", "CameraBackup/../secret")
        assert False, "unsafe path must be rejected"
    except ValueError:
        pass
