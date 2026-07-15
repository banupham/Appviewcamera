import pytest

import asyncio

from appviewcamera_gateway.discovery import camera_candidates, normalize_subnets, scan_subnets, without_added_cameras


def test_subnet_scan_is_bounded():
    with pytest.raises(ValueError, match="vượt giới hạn"):
        normalize_subnets(("192.168.0.0/16",), 256)


def test_small_subnet_is_allowed():
    networks = normalize_subnets(("192.0.2.0/30",), 4)
    assert [str(network) for network in networks] == ["192.0.2.0/30"]


def test_subnet_scan_excludes_gateway_own_address():
    network = normalize_subnets(("127.0.0.0/30",), 4)
    results = asyncio.run(
        scan_subnets(network, (1,), 0.01, excluded_hosts={"127.0.0.1", "127.0.0.2"})
    )
    assert results == []


def test_port_80_is_metadata_not_a_separate_camera_result():
    results = camera_candidates([
        {"host": "192.0.2.10", "port": 80, "source": "onvif", "service_url": "http://192.0.2.10/onvif", "onvif_uuid": "uuid-1"},
        {"host": "192.0.2.10", "port": 554, "source": "tcp_scan", "service_url": None},
        {"host": "192.0.2.11", "port": 80, "source": "tcp_scan", "service_url": None},
    ])

    assert [(item["host"], item["port"]) for item in results] == [("192.0.2.10", 554)]
    assert results[0]["onvif_uuid"] == "uuid-1"


def test_unconfigured_camera_remains_available_and_added_camera_is_hidden():
    candidates = [
        {"host": "192.0.2.10", "port": 554, "source": "tcp_scan", "service_url": None},
        {"host": "192.0.2.11", "port": 554, "source": "tcp_scan", "service_url": None},
    ]
    cameras = [{"id": "one", "host": "192.0.2.10", "port": 554}]

    results = without_added_cameras(candidates, cameras)

    assert [(item["host"], item["port"]) for item in results] == [("192.0.2.11", 554)]
