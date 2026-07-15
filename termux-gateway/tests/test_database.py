from appviewcamera_gateway.database import GatewayDatabase


def test_discovery_candidates_are_upserted(gateway_home):
    database = GatewayDatabase(gateway_home / "data" / "gateway.db")
    database.save_candidates(
        [{"host": "192.0.2.2", "port": 554, "source": "tcp_scan", "service_url": None}]
    )
    database.save_candidates(
        [{"host": "192.0.2.2", "port": 554, "source": "onvif", "service_url": "http://192.0.2.2/onvif"}]
    )
    rows = database.list_candidates()
    assert len(rows) == 1
    assert rows[0]["source"] == "onvif"
    assert rows[0]["service_url"] == "http://192.0.2.2/onvif"


def test_repeated_discovery_does_not_duplicate_sqlite_rows(gateway_home):
    database = GatewayDatabase(gateway_home / "data" / "gateway.db")
    candidate = {"host": "192.0.2.20", "port": 554, "source": "tcp_scan", "service_url": None}

    database.save_candidates([candidate])
    database.save_candidates([candidate])

    assert len(database.list_candidates()) == 1
