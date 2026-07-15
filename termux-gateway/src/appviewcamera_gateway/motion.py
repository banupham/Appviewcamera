from __future__ import annotations

import asyncio
import logging
import socket
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.request import HTTPDigestAuthHandler, HTTPPasswordMgrWithDefaultRealm, Request, build_opener

from .config import GatewaySettings, read_secrets
from .database import GatewayDatabase


LOGGER = logging.getLogger("appviewcamera.motion")
MOTION_TYPES = (
    b"<eventType>VMD</eventType>",
    b"<eventType>linedetection</eventType>",
    b"<eventType>fielddetection</eventType>",
    b"<eventType>regionEntrance</eventType>",
)


class MotionMonitor:
    """Reads Hikvision ISAPI events without continuously decoding camera video."""

    def __init__(
        self,
        settings: GatewaySettings,
        database: GatewayDatabase,
        camera_provider: Callable[[], list[dict]],
    ):
        self.settings = settings
        self.database = database
        self.camera_provider = camera_provider
        self.manager_task: asyncio.Task | None = None
        self.camera_tasks: dict[str, asyncio.Task] = {}
        self.last_errors: dict[str, str] = {}
        self.last_events: dict[str, int] = {}

    def start(self) -> None:
        self.manager_task = asyncio.create_task(self._manage(), name="motion-manager")

    async def stop(self) -> None:
        if self.manager_task:
            self.manager_task.cancel()
        for task in self.camera_tasks.values():
            task.cancel()
        tasks = list(self.camera_tasks.values())
        if self.manager_task:
            tasks.append(self.manager_task)
        await asyncio.gather(*tasks, return_exceptions=True)
        self.camera_tasks.clear()
        self.manager_task = None

    def status(self) -> dict:
        return {
            "monitored_cameras": len(self.camera_tasks),
            "last_events": dict(self.last_events),
            "last_errors": dict(self.last_errors),
        }

    async def _manage(self) -> None:
        while True:
            enabled = {
                str(camera["id"]): camera
                for camera in self.camera_provider()
                if camera.get("enabled", True) and camera.get("motion_enabled", False)
            }
            for camera_id, camera in enabled.items():
                task = self.camera_tasks.get(camera_id)
                if task is None or task.done():
                    self.camera_tasks[camera_id] = asyncio.create_task(
                        self._monitor(camera), name=f"motion-{camera_id}"
                    )
            for camera_id in set(self.camera_tasks) - set(enabled):
                self.camera_tasks.pop(camera_id).cancel()
            await asyncio.sleep(15)

    async def _monitor(self, camera: dict) -> None:
        camera_id = str(camera["id"])
        retry = (5, 10, 30, 60)
        failures = 0
        while True:
            try:
                detected = await asyncio.to_thread(self._read_event_stream, camera)
                if detected:
                    detected_at = int(time.time() * 1000)
                    self.database.record_motion_event(camera_id, detected_at)
                    self.last_events[camera_id] = detected_at
                    self.last_errors.pop(camera_id, None)
                    failures = 0
                    await asyncio.sleep(10)
                else:
                    failures = 0
            except asyncio.CancelledError:
                raise
            except Exception as error:
                self.last_errors[camera_id] = str(error)[-300:]
                LOGGER.warning("Motion stream %s unavailable: %s", camera_id, error)
                await asyncio.sleep(retry[min(failures, len(retry) - 1)])
                failures += 1

    def _read_event_stream(self, camera: dict) -> bool:
        camera_id = str(camera["id"])
        secrets = read_secrets(self.settings.secrets_path)
        password = secrets.get(str(camera.get("secret_ref", "")), "")
        username = str(camera.get("username", ""))
        host = str(camera["host"])
        port = int(camera.get("event_port", 80))
        path = str(camera.get("event_path") or "ISAPI/Event/notification/alertStream").lstrip("/")
        url = f"http://{host}:{port}/{path}"
        passwords = HTTPPasswordMgrWithDefaultRealm()
        passwords.add_password(None, url, username, password)
        opener = build_opener(HTTPDigestAuthHandler(passwords))
        request = Request(url, headers={"Accept": "multipart/mixed, application/xml"})
        deadline = time.monotonic() + 75
        buffer = b""
        try:
            with opener.open(request, timeout=20) as response:
                while time.monotonic() < deadline:
                    chunk = response.read(4096)
                    if not chunk:
                        return False
                    buffer = (buffer + chunk)[-32_768:]
                    active = b"<eventState>active</eventState>" in buffer
                    if active and any(event_type in buffer for event_type in MOTION_TYPES):
                        return True
        except (HTTPError, URLError, socket.timeout, OSError) as error:
            raise RuntimeError(f"ISAPI {camera_id}: {error}") from error
        return False
