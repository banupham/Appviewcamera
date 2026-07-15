import json

from appviewcamera_gateway.drive_oauth import DriveOAuthManager


def test_extracts_token_without_exposing_other_output():
    token = DriveOAuthManager._extract_token(
        'NOTICE: Complete\n{"access_token":"secret","token_type":"Bearer","refresh_token":"refresh"}\n'
    )

    assert token["access_token"] == "secret"
    assert token["refresh_token"] == "refresh"


def test_public_session_never_contains_token(gateway_home):
    from appviewcamera_gateway.storage import GoogleDriveStore

    manager = DriveOAuthManager(GoogleDriveStore(gateway_home))
    try:
        manager.get("missing")
        assert False, "missing session must fail"
    except ValueError as error:
        assert "token" not in json.dumps(str(error)).lower()


def test_rclone_local_authorization_url_accepts_localhost():
    module = __import__("appviewcamera_gateway.drive_oauth", fromlist=["LOCAL_URL_PATTERN"])
    match = module.LOCAL_URL_PATTERN.search(
        "NOTICE: link: http://localhost:53682/auth?state=abc123"
    )

    assert match is not None
    assert match.group(0) == "http://localhost:53682/auth?state=abc123"


def test_localhost_authorization_url_is_proxied_through_loopback(gateway_home):
    from appviewcamera_gateway.storage import GoogleDriveStore

    manager = DriveOAuthManager(GoogleDriveStore(gateway_home))
    captured = []
    manager._request_local = lambda path: captured.append(path) or {
        "status": 302,
        "headers": {"Location": "https://accounts.google.com/o/oauth2/auth?state=abc"},
        "body_base64": "",
    }

    result = manager._external_authorization_url("http://localhost:53682/auth?state=abc")

    assert captured == ["/auth?state=abc"]
    assert result.startswith("https://accounts.google.com/")
