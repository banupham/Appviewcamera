import json
from pathlib import Path

import pytest

from appviewcamera_gateway.config import write_secrets


@pytest.fixture
def gateway_home(tmp_path: Path) -> Path:
    config = tmp_path / "config"
    config.mkdir()
    (config / "gateway.json").write_text(
        json.dumps(
            {
                "api": {"host": "127.0.0.1", "port": 18080},
                "database": {"path": "data/gateway.db"},
                "secrets_file": "config/secrets.env",
                "mediamtx": {
                    "binary": "bin/mediamtx",
                    "generated_config": "run/mediamtx.yml",
                    "rtsp_port": 8554,
                },
                "discovery": {
                    "enabled": False,
                    "subnets": ["192.0.2.0/30"],
                    "ports": [554],
                    "timeout_seconds": 0.1,
                    "max_hosts": 16,
                },
            },
        ),
        encoding="utf-8",
    )
    (config / "cameras.json").write_text('{"cameras": []}\n', encoding="utf-8")
    (config / "google-drives.json").write_text('{"accounts": [], "policy": {}}\n', encoding="utf-8")
    (config / "rclone.conf").write_text("", encoding="utf-8")
    write_secrets(config / "secrets.env", {"API_TOKEN": "test-token"})
    return tmp_path
