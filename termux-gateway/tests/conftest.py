from pathlib import Path

import pytest
import yaml

from appviewcamera_gateway.config import write_secrets


@pytest.fixture
def gateway_home(tmp_path: Path) -> Path:
    config = tmp_path / "config"
    config.mkdir()
    (config / "gateway.yaml").write_text(
        yaml.safe_dump(
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
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    (config / "cameras.yaml").write_text("cameras: []\n", encoding="utf-8")
    write_secrets(config / "secrets.env", {"API_TOKEN": "test-token"})
    return tmp_path
