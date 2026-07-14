from fastapi.testclient import TestClient

from appviewcamera_gateway.api import create_app


def test_api_requires_bearer_token(gateway_home):
    with TestClient(create_app(gateway_home)) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/api/status").status_code == 401
        response = client.get("/api/status", headers={"Authorization": "Bearer test-token"})
        assert response.status_code == 200
        assert response.json()["mediamtx"]["state"] == "UNAVAILABLE"


def test_camera_crud_never_returns_password(gateway_home):
    headers = {"Authorization": "Bearer test-token"}
    body = {
        "id": "camera01",
        "name": "Camera 01",
        "host": "192.0.2.20",
        "username": "admin",
        "password": "very-secret",
        "main_path": "live/main",
    }
    with TestClient(create_app(gateway_home)) as client:
        response = client.put("/api/cameras/camera01", json=body, headers=headers)
        assert response.status_code == 200
        assert "password" not in response.json()
        listed = client.get("/api/cameras", headers=headers).json()
        assert "very-secret" not in str(listed)
        assert client.delete("/api/cameras/camera01", headers=headers).json() == {"deleted": True}
