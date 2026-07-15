import asyncio
import json

from appviewcamera_gateway.api import GatewayRouter, create_runtime


def make_router(gateway_home):
    loop = asyncio.new_event_loop()
    return GatewayRouter(create_runtime(gateway_home), loop), loop


def test_api_requires_bearer_token(gateway_home):
    router, loop = make_router(gateway_home)
    try:
        assert router.route("GET", "/health", "")[0] == 200
        assert router.route("GET", "/api/status", "")[0] == 401
        code, response = router.route("GET", "/api/status", "Bearer test-token")
        assert code == 200
        assert response["mediamtx"]["state"] == "UNAVAILABLE"
    finally:
        loop.close()


def test_camera_crud_never_returns_password(gateway_home):
    router, loop = make_router(gateway_home)
    headers = "Bearer test-token"
    body = json.dumps(
        {
            "id": "camera01",
            "name": "Camera 01",
            "host": "192.0.2.20",
            "username": "admin",
            "password": "very-secret",
            "main_path": "live/main",
        }
    ).encode()
    try:
        code, response = router.route("PUT", "/api/cameras/camera01", headers, body)
        assert code == 200
        assert "password" not in response
        code, listed = router.route("GET", "/api/cameras", headers)
        assert code == 200
        assert "very-secret" not in str(listed)
        assert router.route("DELETE", "/api/cameras/camera01", headers)[1] == {"deleted": True}
    finally:
        loop.close()


def test_added_camera_is_hidden_from_discovery_api(gateway_home):
    router, loop = make_router(gateway_home)
    headers = "Bearer test-token"
    router.runtime.database.save_candidates([
        {"host": "192.0.2.20", "port": 80, "source": "tcp_scan", "service_url": None},
        {"host": "192.0.2.20", "port": 554, "source": "tcp_scan", "service_url": None},
        {"host": "192.0.2.21", "port": 554, "source": "tcp_scan", "service_url": None},
    ])
    router.runtime.camera_store.upsert({
        "id": "camera01", "host": "192.0.2.20", "port": 554, "main_path": "live"
    })
    try:
        code, candidates = router.route("GET", "/api/discovery/candidates", headers)
        assert code == 200
        assert [(item["host"], item["port"]) for item in candidates] == [("192.0.2.21", 554)]
    finally:
        loop.close()


def test_drive_api_never_returns_oauth_token(gateway_home):
    router, loop = make_router(gateway_home)
    headers = "Bearer test-token"
    oauth_token = json.dumps({"access_token": "secret-access", "token_type": "Bearer"})
    body = json.dumps({"id": "drive01", "display_name": "Drive 01", "oauth_token": oauth_token}).encode()
    try:
        code, created = router.route("POST", "/api/storage/drives", headers, body)
        assert code == 201
        assert "secret-access" not in str(created)
        code, listed = router.route("GET", "/api/storage/drives", headers)
        assert code == 200
        assert "secret-access" not in str(listed)
        assert router.route("DELETE", "/api/storage/drives/drive01", headers)[1] == {"deleted": True}
    finally:
        loop.close()


def test_recording_can_be_enabled_through_api(gateway_home):
    router, loop = make_router(gateway_home)
    headers = "Bearer test-token"
    try:
        code, initial = router.route("GET", "/api/recording", headers)
        assert code == 200
        assert initial["enabled"] is False

        code, updated = router.route(
            "PUT",
            "/api/recording",
            headers,
            json.dumps({"enabled": True, "local_retention_minutes": 30}).encode(),
        )
        assert code == 200
        assert updated["enabled"] is True
        assert updated["local_retention_minutes"] == 30
    finally:
        loop.close()


def test_recording_protection_endpoint(gateway_home):
    router, loop = make_router(gateway_home)
    headers = "Bearer test-token"
    database = router.runtime.database
    database.upsert_clip(
        {
            "id": "clip01",
            "camera_id": "camera01",
            "relative_path": "camera01/clip.mp4",
            "started_at_ms": 1,
            "duration_ms": 1000,
            "size_bytes": 10,
            "modified_ns": 1,
        }
    )
    try:
        code, clip = router.route(
            "PUT",
            "/api/recordings/clip01/protection",
            headers,
            json.dumps({"protected": True}).encode(),
        )
        assert code == 200
        assert clip["protected"] == 1
    finally:
        loop.close()


def test_drive_oauth_start_is_exposed_without_returning_token(gateway_home, monkeypatch):
    router, loop = make_router(gateway_home)
    monkeypatch.setattr(
        router.runtime.drive_oauth,
        "start",
        lambda remote_id, display_name: {
            "session_id": "session01",
            "remote_id": remote_id,
            "display_name": display_name,
            "status": "WAITING_BROWSER",
            "authorization_url": "https://accounts.google.com/example",
            "error": None,
        },
    )
    try:
        code, response = router.route(
            "POST",
            "/api/storage/drives/oauth/start",
            "Bearer test-token",
            json.dumps({"id": "drive01", "display_name": "Drive 01"}).encode(),
        )
        assert code == 201
        assert response["session_id"] == "session01"
        assert "access_token" not in response
    finally:
        loop.close()
