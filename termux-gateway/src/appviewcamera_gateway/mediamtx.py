from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
from urllib.parse import quote

from .config import CameraStore, GatewaySettings, read_secrets


LOGGER = logging.getLogger("appviewcamera.mediamtx")


def camera_source(camera: dict, secrets: dict[str, str]) -> str:
    username = quote(str(camera.get("username", "")), safe="")
    password = quote(secrets.get(str(camera.get("secret_ref", "")), ""), safe="")
    credentials = ""
    if username:
        credentials = username
        if password:
            credentials += f":{password}"
        credentials += "@"
    path = "/" + str(camera.get("main_path", "")).lstrip("/")
    return f"rtsp://{credentials}{camera['host']}:{int(camera.get('port', 554))}{path}"


def render_mediamtx_config(settings: GatewaySettings, cameras: list[dict]) -> dict:
    secrets = read_secrets(settings.secrets_path)
    paths: dict[str, dict] = {}
    for camera in cameras:
        if not camera.get("enabled", True):
            continue
        paths[str(camera.get("relay_path") or camera["id"])] = {
            "source": camera_source(camera, secrets),
            "rtspTransport": "tcp",
            "sourceOnDemand": True,
        }
    return {
        "logLevel": "info",
        "rtsp": True,
        "rtspAddress": f":{settings.mediamtx_rtsp_port}",
        "rtmp": False,
        "hls": False,
        "webrtc": False,
        "srt": False,
        "api": True,
        "apiAddress": "127.0.0.1:9997",
        "paths": paths,
    }


def write_mediamtx_config(settings: GatewaySettings, cameras: list[dict]) -> Path:
    settings.mediamtx_config.parent.mkdir(parents=True, exist_ok=True)
    temporary = settings.mediamtx_config.with_suffix(".tmp")
    # JSON là tập con hợp lệ của YAML 1.2 và MediaMTX đọc được trực tiếp.
    temporary.write_text(json.dumps(render_mediamtx_config(settings, cameras), indent=2), encoding="utf-8")
    temporary.replace(settings.mediamtx_config)
    return settings.mediamtx_config


class MediaMtxSupervisor:
    def __init__(self, settings: GatewaySettings, camera_store: CameraStore):
        self.settings = settings
        self.camera_store = camera_store
        self.process: asyncio.subprocess.Process | None = None
        self.monitor_task: asyncio.Task | None = None
        self.stopping = False
        self.last_error: str | None = None
        self.restart_count = 0
        self._log_handle = None

    def status(self) -> dict:
        running = self.process is not None and self.process.returncode is None
        return {
            "state": "RUNNING" if running else ("UNAVAILABLE" if not self.settings.mediamtx_binary.exists() else "STOPPED"),
            "pid": self.process.pid if running else None,
            "restart_count": self.restart_count,
            "last_error": self.last_error,
            "rtsp_port": self.settings.mediamtx_rtsp_port,
        }

    async def start(self) -> None:
        write_mediamtx_config(self.settings, self.camera_store.list())
        if not self.settings.mediamtx_binary.exists():
            self.last_error = f"Không tìm thấy {self.settings.mediamtx_binary}"
            LOGGER.warning(self.last_error)
            return
        self.stopping = False
        await self._spawn()
        self.monitor_task = asyncio.create_task(self._monitor(), name="mediamtx-monitor")

    async def _spawn(self) -> None:
        log_path = self.settings.home / "logs" / "mediamtx.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        self._log_handle = log_path.open("ab", buffering=0)
        try:
            self.process = await asyncio.create_subprocess_exec(
                str(self.settings.mediamtx_binary),
                str(self.settings.mediamtx_config),
                stdout=self._log_handle,
                stderr=asyncio.subprocess.STDOUT,
            )
            self.last_error = None
        except OSError as error:
            self.last_error = str(error)
            self.process = None
            self._close_log()

    async def _monitor(self) -> None:
        schedule = (5, 10, 30, 60)
        while not self.stopping:
            if self.process is None:
                return
            return_code = await self.process.wait()
            self._close_log()
            if self.stopping:
                return
            self.last_error = f"MediaMTX dừng với mã {return_code}"
            delay = schedule[min(self.restart_count, len(schedule) - 1)]
            self.restart_count += 1
            LOGGER.error("%s; thử lại sau %s giây", self.last_error, delay)
            await asyncio.sleep(delay)
            if not self.stopping:
                await self._spawn()

    async def reload(self) -> None:
        await self.stop()
        self.restart_count = 0
        await self.start()

    async def stop(self) -> None:
        self.stopping = True
        if self.process and self.process.returncode is None:
            self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), 5)
            except asyncio.TimeoutError:
                self.process.kill()
                await self.process.wait()
        if self.monitor_task:
            self.monitor_task.cancel()
            await asyncio.gather(self.monitor_task, return_exceptions=True)
            self.monitor_task = None
        self.process = None
        self._close_log()

    def _close_log(self) -> None:
        if self._log_handle:
            self._log_handle.close()
            self._log_handle = None
