import pytest

import asyncio

from appviewcamera_gateway.discovery import normalize_subnets, scan_subnets


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
