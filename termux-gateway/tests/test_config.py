from appviewcamera_gateway.config import CameraStore, GatewaySettings, read_secrets
from appviewcamera_gateway.mediamtx import camera_source, render_mediamtx_config


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


def test_mediamtx_only_contains_enabled_cameras(gateway_home):
    settings = GatewaySettings.load(gateway_home)
    cameras = [
        {"id": "one", "host": "192.0.2.1", "main_path": "live", "relay_path": "one", "enabled": True},
        {"id": "two", "host": "192.0.2.2", "main_path": "live", "relay_path": "two", "enabled": False},
    ]
    rendered = render_mediamtx_config(settings, cameras)
    assert list(rendered["paths"]) == ["one"]
    assert rendered["paths"]["one"]["sourceOnDemand"] is True
