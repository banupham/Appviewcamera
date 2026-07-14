from __future__ import annotations

import asyncio
import logging
import os
import signal
from pathlib import Path

from .api import GatewayHttpServer, GatewayRouter, create_runtime


async def run() -> None:
    home = Path(os.environ.get("APPVIEWCAMERA_HOME", "~/appviewcamera")).expanduser().resolve()
    runtime = create_runtime(home)
    if not runtime.settings.api_token:
        raise SystemExit(f"Thiếu API_TOKEN trong {runtime.settings.secrets_path}. Hãy chạy scripts/install.sh.")
    log_dir = home / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        handlers=[logging.FileHandler(log_dir / "gateway.log", encoding="utf-8"), logging.StreamHandler()],
    )
    loop = asyncio.get_running_loop()
    stop_event = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop_event.set)
        except NotImplementedError:
            pass
    await runtime.start()
    server = GatewayHttpServer(runtime.settings, GatewayRouter(runtime, loop))
    server.start()
    try:
        await stop_event.wait()
    finally:
        await asyncio.to_thread(server.stop)
        await runtime.stop()


def main() -> None:
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
