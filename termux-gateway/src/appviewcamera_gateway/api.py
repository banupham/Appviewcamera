from __future__ import annotations

import asyncio
import hmac
import json
import logging
import re
import threading
from concurrent.futures import TimeoutError as FutureTimeoutError
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Coroutine
from urllib.parse import parse_qs, unquote, urlsplit

from . import __version__
from .config import CameraStore, GatewaySettings
from .database import GatewayDatabase
from .discovery import discover_cameras
from .mediamtx import MediaMtxSupervisor
from .storage import GoogleDriveStore
from .recording import RecordingManager, RecordingWorker


LOGGER = logging.getLogger("appviewcamera.api")


@dataclass(frozen=True)
class FilePayload:
    path: Path
    content_type: str = "video/mp4"


class GatewayRuntime:
    def __init__(self, settings: GatewaySettings):
        self.settings = settings
        self.camera_store = CameraStore(settings)
        self.database = GatewayDatabase(settings.database_path)
        self.mediamtx = MediaMtxSupervisor(settings, self.camera_store)
        self.drive_store = GoogleDriveStore(settings.home)
        self.recording = RecordingManager(settings, self.database)
        self.recording_worker = RecordingWorker(
            self.recording, self.database, self.drive_store, self.camera_store.list
        )
        self.discovery_lock = asyncio.Lock()
        self.discovery_task: asyncio.Task | None = None
        self.last_discovery_error: str | None = None

    async def start(self) -> None:
        await self.mediamtx.start()
        self.recording_worker.start()
        if self.settings.discovery_enabled:
            self.discovery_task = asyncio.create_task(self._periodic_discovery(), name="camera-discovery")

    async def stop(self) -> None:
        await self.recording_worker.stop()
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


class GatewayRouter:
    def __init__(self, runtime: GatewayRuntime, loop: asyncio.AbstractEventLoop):
        self.runtime = runtime
        self.loop = loop

    def route(self, method: str, raw_path: str, authorization: str, body: bytes = b"") -> tuple[int, Any]:
        parsed_url = urlsplit(raw_path)
        path = parsed_url.path
        query = parse_qs(parsed_url.query)
        if method == "GET" and path == "/health":
            return 200, {"status": "ok", "version": __version__}
        expected = self.runtime.settings.api_token
        supplied = authorization.removeprefix("Bearer ") if authorization.startswith("Bearer ") else ""
        if not expected:
            return 503, {"detail": "Gateway chưa có API_TOKEN"}
        if not hmac.compare_digest(expected, supplied):
            return 401, {"detail": "Bearer token không hợp lệ"}
        try:
            if method == "GET" and path == "/api/status":
                return 200, {
                    "status": "ONLINE",
                    "version": __version__,
                    "camera_count": len(self.runtime.camera_store.list()),
                    "candidate_count": len(self.runtime.database.list_candidates()),
                    "discovery_running": self.runtime.discovery_lock.locked(),
                    "last_discovery_error": self.runtime.last_discovery_error,
                    "mediamtx": self.runtime.mediamtx.status(),
                }
            if method == "GET" and path == "/api/cameras":
                return 200, self.runtime.camera_store.list()
            if method == "GET" and path == "/api/storage/drives":
                return 200, self.runtime.drive_store.list()
            if method == "POST" and path == "/api/storage/drives":
                request = json.loads(body.decode("utf-8"))
                return 201, self.runtime.drive_store.add(
                    str(request.get("id", "")),
                    str(request.get("display_name", "")),
                    str(request.get("oauth_token", "")),
                )
            if method == "GET" and path == "/api/recording":
                return 200, {
                    **self.runtime.recording.status(),
                    **self.runtime.recording_worker.status(),
                }
            if method == "PUT" and path == "/api/recording":
                request = json.loads(body.decode("utf-8"))
                result = self.runtime.recording.update(
                    bool(request.get("enabled", False)),
                    int(request["local_retention_minutes"]) if "local_retention_minutes" in request else None,
                )
                self._await(self.runtime.mediamtx.reload(), 20)
                return 200, result
            if method == "GET" and path == "/api/recordings":
                self.runtime.recording.scan(self.runtime.camera_store.list())
                camera_id = query.get("camera_id", [None])[0]
                from_ms = int(query["from_ms"][0]) if "from_ms" in query else None
                to_ms = int(query["to_ms"][0]) if "to_ms" in query else None
                limit = int(query.get("limit", ["200"])[0])
                clips = self.runtime.database.list_clips(camera_id, from_ms, to_ms, limit)
                return 200, {"count": len(clips), "clips": clips}
            if path.startswith("/api/recordings/") and path.endswith("/content") and method in ("GET", "HEAD"):
                clip_id = unquote(path.removeprefix("/api/recordings/").removesuffix("/content"))
                clip_path = self.runtime.recording.playback_path(
                    clip_id, self.runtime.drive_store
                )
                if clip_path is None:
                    return 404, {"detail": "Không tìm thấy clip"}
                return 200, FilePayload(clip_path)
            if method == "GET" and path == "/api/discovery/candidates":
                return 200, self.runtime.database.list_candidates()
            if method == "POST" and path == "/api/discovery/scan":
                candidates = self._await(self.runtime.scan(), 180)
                return 200, {"count": len(candidates), "candidates": candidates}
            if method == "POST" and path == "/api/mediamtx/restart":
                self._await(self.runtime.mediamtx.reload(), 20)
                return 200, self.runtime.mediamtx.status()
            if path.startswith("/api/cameras/"):
                camera_id = unquote(path.removeprefix("/api/cameras/"))
                if method == "PUT":
                    request = json.loads(body.decode("utf-8"))
                    if request.get("id") != camera_id:
                        return 400, {"detail": "camera_id không khớp nội dung"}
                    password = request.pop("password", None)
                    saved = self.runtime.camera_store.upsert(request, password)
                    self._await(self.runtime.mediamtx.reload(), 20)
                    return 200, saved
                if method == "DELETE":
                    if not self.runtime.camera_store.delete(camera_id):
                        return 404, {"detail": "Không tìm thấy camera"}
                    self._await(self.runtime.mediamtx.reload(), 20)
                    return 200, {"deleted": True}
            if path.startswith("/api/storage/drives/"):
                suffix = path.removeprefix("/api/storage/drives/")
                if suffix.endswith("/refresh") and method == "POST":
                    remote_id = unquote(suffix.removesuffix("/refresh"))
                    return 200, self.runtime.drive_store.refresh(remote_id)
                remote_id = unquote(suffix)
                if method == "DELETE":
                    if not self.runtime.drive_store.delete(remote_id):
                        return 404, {"detail": "Không tìm thấy tài khoản Google Drive"}
                    return 200, {"deleted": True}
            return 404, {"detail": "Không tìm thấy endpoint"}
        except (ValueError, json.JSONDecodeError) as error:
            return 400, {"detail": str(error)}
        except RuntimeError as error:
            return 409, {"detail": str(error)}
        except FutureTimeoutError:
            return 504, {"detail": "Gateway xử lý quá thời gian"}
        except Exception as error:
            LOGGER.exception("API request thất bại")
            return 500, {"detail": str(error)}

    def _await(self, operation: Coroutine[Any, Any, Any], timeout: float) -> Any:
        if self.loop.is_running():
            return asyncio.run_coroutine_threadsafe(operation, self.loop).result(timeout=timeout)
        return self.loop.run_until_complete(operation)


class GatewayHttpServer:
    def __init__(self, settings: GatewaySettings, router: GatewayRouter):
        self.router = router
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                self._handle("GET")

            def do_HEAD(self) -> None:
                self._handle("HEAD")

            def do_POST(self) -> None:
                self._handle("POST")

            def do_PUT(self) -> None:
                self._handle("PUT")

            def do_DELETE(self) -> None:
                self._handle("DELETE")

            def _handle(self, method: str) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                if length > 65_536:
                    self._write(413, {"detail": "Nội dung request quá lớn"})
                    return
                body = self.rfile.read(length) if length else b""
                code, payload = outer.router.route(
                    method, self.path, self.headers.get("Authorization", ""), body
                )
                self._write(code, payload)

            def _write(self, code: int, payload: Any) -> None:
                if isinstance(payload, FilePayload):
                    self._write_file(code, payload)
                    return
                encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def _write_file(self, code: int, payload: FilePayload) -> None:
                try:
                    size = payload.path.stat().st_size
                    start, end = 0, max(0, size - 1)
                    range_header = self.headers.get("Range", "")
                    if range_header:
                        match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
                        if not match or (not match.group(1) and not match.group(2)):
                            self.send_error(416)
                            return
                        if match.group(1):
                            start = int(match.group(1))
                            end = int(match.group(2)) if match.group(2) else end
                        else:
                            suffix = int(match.group(2))
                            start = max(0, size - suffix)
                        if start >= size or end < start:
                            self.send_response(416)
                            self.send_header("Content-Range", f"bytes */{size}")
                            self.end_headers()
                            return
                        end = min(end, size - 1)
                        code = 206
                    length = max(0, end - start + 1)
                    self.send_response(code)
                    self.send_header("Content-Type", payload.content_type)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Length", str(length))
                    if code == 206:
                        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                    self.end_headers()
                    if self.command == "HEAD":
                        return
                    with payload.path.open("rb") as handle:
                        handle.seek(start)
                        remaining = length
                        while remaining > 0:
                            chunk = handle.read(min(64 * 1024, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)
                except OSError:
                    LOGGER.exception("Không đọc được clip %s", payload.path)

            def log_message(self, format: str, *args: Any) -> None:
                LOGGER.info("HTTP %s", format % args)

        self.server = ThreadingHTTPServer((settings.api_host, settings.api_port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, name="gateway-http", daemon=True)

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def create_runtime(home: Path | None = None) -> GatewayRuntime:
    return GatewayRuntime(GatewaySettings.load(home))
