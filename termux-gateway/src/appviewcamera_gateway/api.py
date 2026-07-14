from __future__ import annotations

import asyncio
import hmac
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field

from . import __version__
from .config import CameraStore, GatewaySettings
from .database import GatewayDatabase
from .discovery import discover_cameras
from .mediamtx import MediaMtxSupervisor


LOGGER = logging.getLogger("appviewcamera.api")
BEARER = HTTPBearer(auto_error=False)


class CameraRequest(BaseModel):
    id: str
    name: str = ""
    host: str
    port: int = Field(default=554, ge=1, le=65535)
    username: str = ""
    password: str | None = None
    main_path: str
    sub_path: str = ""
    relay_path: str = ""
    enabled: bool = True
    record_enabled: bool = True
    motion_enabled: bool = False
    audio_enabled: bool = True


class GatewayRuntime:
    def __init__(self, settings: GatewaySettings):
        self.settings = settings
        self.camera_store = CameraStore(settings)
        self.database = GatewayDatabase(settings.database_path)
        self.mediamtx = MediaMtxSupervisor(settings, self.camera_store)
        self.discovery_lock = asyncio.Lock()
        self.discovery_task: asyncio.Task | None = None
        self.last_discovery_error: str | None = None

    async def start(self) -> None:
        await self.mediamtx.start()
        if self.settings.discovery_enabled:
            self.discovery_task = asyncio.create_task(self._periodic_discovery(), name="camera-discovery")

    async def stop(self) -> None:
        if self.discovery_task:
            self.discovery_task.cancel()
            await asyncio.gather(self.discovery_task, return_exceptions=True)
        await self.mediamtx.stop()

    async def scan(self) -> list[dict]:
        if self.discovery_lock.locked():
            raise RuntimeError("Một lượt quét camera đang chạy")
        async with self.discovery_lock:
            try:
                candidates = await discover_cameras(self.settings)
                await asyncio.to_thread(self.database.save_candidates, candidates)
                self.last_discovery_error = None
                return candidates
            except Exception as error:
                self.last_discovery_error = str(error)
                LOGGER.exception("Quét camera thất bại")
                raise

    async def _periodic_discovery(self) -> None:
        await asyncio.sleep(5)
        while True:
            try:
                await self.scan()
            except Exception:
                pass
            await asyncio.sleep(self.settings.discovery_interval_seconds)


def create_app(home: Path | None = None) -> FastAPI:
    settings = GatewaySettings.load(home)
    runtime = GatewayRuntime(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        await runtime.start()
        try:
            yield
        finally:
            await runtime.stop()

    app = FastAPI(title="AppViewCamera Gateway", version=__version__, lifespan=lifespan)
    app.state.runtime = runtime

    async def authorize(credentials: HTTPAuthorizationCredentials | None = Depends(BEARER)) -> None:
        expected = settings.api_token
        supplied = credentials.credentials if credentials and credentials.scheme.lower() == "bearer" else ""
        if not expected:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Gateway chưa có API_TOKEN")
        if not hmac.compare_digest(expected, supplied):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Bearer token không hợp lệ")

    @app.get("/health")
    async def health() -> dict:
        return {"status": "ok", "version": __version__}

    @app.get("/api/status", dependencies=[Depends(authorize)])
    async def gateway_status() -> dict:
        return {
            "status": "ONLINE",
            "version": __version__,
            "camera_count": len(runtime.camera_store.list()),
            "candidate_count": len(runtime.database.list_candidates()),
            "discovery_running": runtime.discovery_lock.locked(),
            "last_discovery_error": runtime.last_discovery_error,
            "mediamtx": runtime.mediamtx.status(),
        }

    @app.get("/api/cameras", dependencies=[Depends(authorize)])
    async def list_cameras() -> list[dict]:
        return runtime.camera_store.list()

    @app.put("/api/cameras/{camera_id}", dependencies=[Depends(authorize)])
    async def save_camera(camera_id: str, request: CameraRequest) -> dict:
        if camera_id != request.id:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "camera_id không khớp nội dung")
        try:
            saved = await asyncio.to_thread(
                runtime.camera_store.upsert,
                request.model_dump(exclude={"password"}),
                request.password,
            )
        except ValueError as error:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(error)) from error
        await runtime.mediamtx.reload()
        return saved

    @app.delete("/api/cameras/{camera_id}", dependencies=[Depends(authorize)])
    async def delete_camera(camera_id: str) -> dict:
        removed = await asyncio.to_thread(runtime.camera_store.delete, camera_id)
        if not removed:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Không tìm thấy camera")
        await runtime.mediamtx.reload()
        return {"deleted": True}

    @app.post("/api/discovery/scan", dependencies=[Depends(authorize)])
    async def scan() -> dict:
        try:
            candidates = await runtime.scan()
        except RuntimeError as error:
            raise HTTPException(status.HTTP_409_CONFLICT, str(error)) from error
        except ValueError as error:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, str(error)) from error
        return {"count": len(candidates), "candidates": candidates}

    @app.get("/api/discovery/candidates", dependencies=[Depends(authorize)])
    async def list_candidates() -> list[dict]:
        return runtime.database.list_candidates()

    @app.post("/api/mediamtx/restart", dependencies=[Depends(authorize)])
    async def restart_mediamtx() -> dict:
        await runtime.mediamtx.reload()
        return runtime.mediamtx.status()

    return app
