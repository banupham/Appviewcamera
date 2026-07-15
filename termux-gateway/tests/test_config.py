import json
import asyncio
from unittest.mock import AsyncMock

import pytest

from appviewcamera_gateway.config import CameraStore, GatewaySettings, read_secrets
from appviewcamera_gateway.mediamtx import MediaMtxSupervisor, camera_source, render_mediamtx_config


def test_camera_password_is_kept_out_of_camera_config(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    store = CameraStore(settings)
    saved = store.upsert(
        {
            "id": "front-door",
            "name": "Cửa trước",
            "host": "192.168.1.20",
            "port": 554,
            "username": "admin@example",
            "main_path": "/Streaming/Channels/101",
        },
        "p@ss word",
    )

    assert "password" not in saved
    assert "p@ss word" not in store.path.read_text(encoding="utf-8")
    assert read_secrets(settings.secrets_path)[saved["secret_ref"]] == "p@ss word"
    source = camera_source(saved, read_secrets(settings.secrets_path))
    assert source == "rtsp://admin%40example:p%40ss%20word@192.168.1.20:554/Streaming/Channels/101"


def test_camera_cannot_be_added_twice_by_host_and_rtsp_port(gateway_home):
    store = CameraStore(GatewaySettings.load(gateway_home))
    common = {"host": "192.168.1.20", "port": 554, "main_path": "live"}
    store.upsert({"id": "camera01", **common})

    with pytest.raises(ValueError, match="camera01"):
        store.upsert({"id": "camera02", **common})


def test_mediamtx_only_contains_enabled_cameras(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    cameras = [
        {"id": "one", "host": "192.0.2.1", "main_path": "live", "relay_path": "one", "enabled": True},
        {"id": "two", "host": "192.0.2.2", "main_path": "live", "relay_path": "two", "enabled": False},
    ]
    rendered = render_mediamtx_config(settings, cameras)
    assert list(rendered["paths"]) == ["one"]
    assert rendered["paths"]["one"]["sourceOnDemand"] is True


def test_mediamtx_records_substream_only_when_globally_enabled(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    recording_path = gateway_home / "config" / "recording.json"
    recording = json.loads(recording_path.read_text(encoding="utf-8"))
    recording["recording"]["enabled"] = True
    recording_path.write_text(json.dumps(recording), encoding="utf-8")
    cameras = [
        {
            "id": "one",
            "host": "192.0.2.1",
            "main_path": "main",
            "sub_path": "sub",
            "relay_path": "one",
            "enabled": True,
            "record_enabled": True,
        }
    ]

    rendered = render_mediamtx_config(settings, cameras)

    assert "record" not in rendered["paths"]["one"]
    assert rendered["paths"]["one_sub"]["source"].endswith("/sub")
    assert rendered["paths"]["one_sub"]["sourceOnDemand"] is True
    record_path = rendered["paths"]["record_one"]
    assert record_path["source"].endswith("/sub")
    assert record_path["record"] is True
    assert record_path["recordSegmentDuration"] == "60s"
    assert record_path["recordDeleteAfter"] == "0s"


def test_hot_reconfigure_does_not_stop_running_mediamtx(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    supervisor = MediaMtxSupervisor(settings, CameraStore(settings))
    supervisor.process = type("RunningProcess", (), {"returncode": None})()
    supervisor.stop = AsyncMock()
    supervisor.start = AsyncMock()

    asyncio.run(supervisor.reconfigure())

    supervisor.stop.assert_not_awaited()
    supervisor.start.assert_not_awaited()
    assert settings.mediamtx_config.exists()
