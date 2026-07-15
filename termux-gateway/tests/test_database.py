from appviewcamera_gateway.database import GatewayDatabase
import sqlite3


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


def test_recording_state_migration_preserves_legacy_rows(gateway_home):
    path = gateway_home / "data" / "legacy.db"
    path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(path) as connection:
        connection.execute(
            """CREATE TABLE recording_clips (
                id TEXT PRIMARY KEY, camera_id TEXT NOT NULL, relative_path TEXT NOT NULL UNIQUE,
                started_at_ms INTEGER NOT NULL, duration_ms INTEGER, size_bytes INTEGER NOT NULL,
                modified_ns INTEGER NOT NULL, local_state TEXT NOT NULL DEFAULT 'AVAILABLE',
                upload_state TEXT NOT NULL DEFAULT 'PENDING', remote_id TEXT, remote_path TEXT,
                upload_attempts INTEGER NOT NULL DEFAULT 0, next_retry_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT, uploaded_at_ms INTEGER, protected INTEGER NOT NULL DEFAULT 0,
                motion INTEGER NOT NULL DEFAULT 0
            )"""
        )
        connection.execute(
            "INSERT INTO recording_clips(id,camera_id,relative_path,started_at_ms,size_bytes,modified_ns,"
            "local_state,upload_state,remote_id,remote_path,uploaded_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            ("old", "camera01", "camera01/old.mp4", 100, 8, 1, "MISSING", "UPLOADED",
             "drive01", "CameraBackup/camera01/old.mp4", 200),
        )

    database = GatewayDatabase(path)
    migrated = database.get_clip("old")

    assert migrated["clip_state"] == "DRIVE_READY"
    assert migrated["remote_file_id"] == "legacy-path:CameraBackup/camera01/old.mp4"
    assert migrated["remote_size_bytes"] == 8
    assert migrated["remote_verified_at_ms"] == 200
