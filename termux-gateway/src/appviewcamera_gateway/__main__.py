from __future__ import annotations

import logging
import os
from pathlib import Path

import uvicorn

from .api import create_app
from .config import GatewaySettings


def main() -> None:
    home = Path(os.environ.get("APPVIEWCAMERA_HOME", "~/appviewcamera")).expanduser().resolve()
    settings = GatewaySettings.load(home)
    if not settings.api_token:
        raise SystemExit(f"Thiếu API_TOKEN trong {settings.secrets_path}. Hãy chạy scripts/install.sh.")
    log_dir = settings.home / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        handlers=[logging.FileHandler(log_dir / "gateway.log", encoding="utf-8"), logging.StreamHandler()],
    )
    uvicorn.run(create_app(home), host=settings.api_host, port=settings.api_port, log_level="info")


if __name__ == "__main__":
    main()
