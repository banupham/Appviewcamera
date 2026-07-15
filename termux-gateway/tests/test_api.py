import asyncio
import json
from dataclasses import replace
from urllib.request import Request, urlopen

from appviewcamera_gateway.api import GatewayHttpServer, GatewayRouter, create_runtime


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
        assert response["gateway_id"] == "gateway-test-01"
        assert response["gateway_name"] == "Gateway Test"
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


def test_recording_content_supports_http_range(gateway_home):
    router, loop = make_router(gateway_home)
    manager = router.runtime.recording
    clip_path = manager.root / "camera01" / "range.mp4"
    clip_path.parent.mkdir(parents=True)
    clip_path.write_bytes(b"0123456789")
    router.runtime.database.upsert_clip({
        "id": "range-clip", "camera_id": "camera01", "relative_path": "camera01/range.mp4",
        "started_at_ms": 1, "duration_ms": 1000, "size_bytes": 10,
        "modified_ns": clip_path.stat().st_mtime_ns,
    })
    server = GatewayHttpServer(replace(router.runtime.settings, api_port=0), router)
    server.start()
    port = server.server.server_address[1]
    try:
        request = Request(
            f"http://127.0.0.1:{port}/api/recordings/range-clip/content",
            headers={"Authorization": "Bearer test-token", "Range": "bytes=2-5"},
        )
        with urlopen(request, timeout=5) as response:
            assert response.status == 206
            assert response.headers["Content-Range"] == "bytes 2-5/10"
            assert response.read() == b"2345"
    finally:
        server.stop()
        loop.close()


def test_playback_timeline_uses_sqlite_and_drive_before_youtube(gateway_home, monkeypatch):
    router, loop = make_router(gateway_home)
    database = router.runtime.database
    database.upsert_clip({
        "id": "indexed-drive", "camera_id": "camera01", "relative_path": "camera01/indexed.mp4",
        "started_at_ms": 1_752_556_800_000, "duration_ms": 60_000, "size_bytes": 8,
        "modified_ns": 1,
    })
    database.mark_uploading("indexed-drive", "drive01", "CameraBackup/camera01/indexed.mp4")
    database.mark_uploaded("indexed-drive", 1, "drive-file-01", 8, 1)
    database.mark_local_missing("indexed-drive")
    database.set_youtube_source("indexed-drive", "PROCESSING", updated_at_ms=2)
    monkeypatch.setattr(router.runtime.recording, "scan", lambda *_: (_ for _ in ()).throw(
        AssertionError("timeline must not scan local, Drive or YouTube")
    ))
    monkeypatch.setattr(router.runtime.drive_store, "list", lambda: (_ for _ in ()).throw(
        AssertionError("timeline must not query Drive")
    ))
    try:
        days_code, days = router.route(
            "GET", "/api/playback/days?camera_id=camera01", "Bearer test-token"
        )
        code, payload = router.route(
            "GET",
            "/api/playback/timeline?camera_id=camera01&from_ms=1752556800000&to_ms=1752643200000",
            "Bearer test-token",
        )
        assert days_code == 200
        assert days["days"][0]["item_count"] == 1
        assert code == 200
        assert payload["count"] == 1
        assert payload["items"][0]["preferred_source"] == "DRIVE_READY"
        assert payload["items"][0]["drive_available"] is True
        assert payload["items"][0]["youtube_available"] is False
        assert payload["items"][0]["status"] == "READY"
    finally:
        loop.close()


def test_playback_youtube_source_has_exact_offset_without_oauth(gateway_home):
    router, loop = make_router(gateway_home)
    database = router.runtime.database
    database.upsert_clip({
        "id": "youtube-item", "camera_id": "camera01", "relative_path": "camera01/youtube.mp4",
        "started_at_ms": 1_000, "duration_ms": 60_000, "size_bytes": 8, "modified_ns": 1,
    })
    database.mark_missing_clips(set())
    database.set_youtube_source("youtube-item", "YOUTUBE_READY", "private-video", 125, 2)
    try:
        code, payload = router.route(
            "GET", "/api/playback/items/youtube-item/sources", "Bearer test-token"
        )
        youtube = next(source for source in payload["sources"] if source["type"] == "YOUTUBE_READY")
        assert code == 200
        assert youtube["start_offset_seconds"] == 125
        assert youtube["watch_url"] == "https://www.youtube.com/watch?v=private-video&t=125s"
        assert "token" not in youtube["watch_url"].lower()
        assert payload["preferred_source"] == "YOUTUBE_READY"
    finally:
        loop.close()


def test_playback_item_without_ready_source_has_no_stream(gateway_home):
    router, loop = make_router(gateway_home)
    database = router.runtime.database
    database.upsert_clip({
        "id": "not-ready", "camera_id": "camera01", "relative_path": "camera01/not-ready.mp4",
        "started_at_ms": 1_000, "duration_ms": 60_000, "size_bytes": 8, "modified_ns": 1,
    })
    database.mark_missing_clips(set())
    database.set_youtube_source("not-ready", "PROCESSING", updated_at_ms=2)
    try:
        code, item = router.route(
            "GET", "/api/playback/items/not-ready", "Bearer test-token"
        )
        stream_code, _ = router.route(
            "GET", "/api/playback/items/not-ready/stream", "Bearer test-token"
        )
        assert code == 200
        assert item["status"] == "PROCESSING"
        assert item["preferred_source"] is None
        assert stream_code == 409
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
