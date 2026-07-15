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
