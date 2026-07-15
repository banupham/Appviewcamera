from __future__ import annotations

import base64
import json
import re
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from .storage import GoogleDriveStore, REMOTE_ID_PATTERN


LOCAL_OAUTH_ORIGIN = "http://127.0.0.1:53682"
LOCAL_URL_PATTERN = re.compile(r'''http://(?:127\.0\.0\.1|localhost):53682/[^\s"'<>]+''')


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


@dataclass
class OAuthSession:
    id: str
    remote_id: str
    display_name: str
    created_at_ms: int
    status: str = "STARTING"
    authorization_url: str | None = None
    error: str | None = None
    process: subprocess.Popen[str] | None = None


class DriveOAuthManager:
    """Completes rclone OAuth while the browser runs on the Viewer device."""

    def __init__(self, drive_store: GoogleDriveStore):
        self.drive_store = drive_store
        self._lock = threading.RLock()
        self._changed = threading.Condition(self._lock)
        self._sessions: dict[str, OAuthSession] = {}

    def start(self, remote_id: str, display_name: str) -> dict[str, Any]:
        normalized_id = remote_id.lower().strip()
        if not REMOTE_ID_PATTERN.fullmatch(normalized_id):
            raise ValueError("ID Drive chỉ gồm a-z, 0-9, _ hoặc -, tối đa 32 ký tự")
        with self._changed:
            self._cleanup()
            if any(item.status in ("STARTING", "WAITING_BROWSER") for item in self._sessions.values()):
                raise RuntimeError("Một phiên đăng nhập Google khác đang chạy")
            session = OAuthSession(
                id=uuid.uuid4().hex,
                remote_id=normalized_id,
                display_name=display_name.strip() or normalized_id,
                created_at_ms=int(time.time() * 1000),
            )
            self._sessions[session.id] = session
            thread = threading.Thread(
                target=self._authorize, args=(session,), name="drive-oauth", daemon=True
            )
            thread.start()
            # Termux can need more than 20 seconds to cold-start rclone and
            # initialize Android's network resolver on slower phones.
            deadline = time.monotonic() + 40
            while session.status == "STARTING" and time.monotonic() < deadline:
                self._changed.wait(timeout=0.5)
            if session.status == "ERROR":
                raise RuntimeError(session.error or "Không khởi tạo được đăng nhập Google")
            if not session.authorization_url:
                session.status = "ERROR"
                session.error = (
                    "rclone chưa tạo URL đăng nhập trong 40 giây; "
                    "hãy thử lại và kiểm tra cổng 53682 không bị tiến trình cũ chiếm"
                )
                self._terminate(session)
                raise RuntimeError(session.error)
            return self._public(session)

    def get(self, session_id: str) -> dict[str, Any]:
        with self._lock:
            session = self._sessions.get(session_id)
            if not session:
                raise ValueError("Phiên đăng nhập Google không tồn tại hoặc đã hết hạn")
            return self._public(session)

    def proxy_callback(self, session_id: str, path: str) -> dict[str, Any]:
        with self._lock:
            session = self._sessions.get(session_id)
            if not session or session.status not in ("WAITING_BROWSER", "SAVING"):
                raise ValueError("Phiên đăng nhập Google không còn chờ callback")
        if not path.startswith("/") or "\r" in path or "\n" in path or len(path) > 8192:
            raise ValueError("OAuth callback không hợp lệ")
        return self._request_local(path)

    def stop(self) -> None:
        with self._lock:
            for session in self._sessions.values():
                self._terminate(session)

    def _authorize(self, session: OAuthSession) -> None:
        output = ""
        try:
            process = subprocess.Popen(
                ["rclone", "authorize", "drive", "--auth-no-open-browser"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
            session.process = process
            assert process.stdout is not None
            for line in process.stdout:
                output += line
                if session.authorization_url is None:
                    match = LOCAL_URL_PATTERN.search(line)
                    if match:
                        external_url = self._external_authorization_url(match.group(0))
                        with self._changed:
                            session.authorization_url = external_url
                            session.status = "WAITING_BROWSER"
                            self._changed.notify_all()
            return_code = process.wait(timeout=10)
            if return_code != 0:
                raise RuntimeError(self._safe_error(output, f"rclone kết thúc với mã {return_code}"))
            token = self._extract_token(output)
            with self._changed:
                session.status = "SAVING"
                self._changed.notify_all()
            self.drive_store.add(
                session.remote_id,
                session.display_name,
                json.dumps(token, ensure_ascii=False, separators=(",", ":")),
            )
            with self._changed:
                session.status = "COMPLETE"
                session.error = None
                self._changed.notify_all()
        except Exception as error:
            with self._changed:
                session.status = "ERROR"
                session.error = self._safe_error(output, str(error))
                self._changed.notify_all()
            self._terminate(session)

    def _external_authorization_url(self, local_url: str) -> str:
        parsed = urlsplit(local_url)
        path = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        response = self._request_local(path)
        location = response["headers"].get("Location")
        if response["status"] not in (301, 302, 303, 307, 308) or not location:
            raise RuntimeError("rclone không chuyển hướng tới trang đăng nhập Google")
        if not str(location).startswith("https://accounts.google.com/"):
            raise RuntimeError("URL đăng nhập Google không hợp lệ")
        return str(location)

    @staticmethod
    def _request_local(path: str) -> dict[str, Any]:
        opener = build_opener(_NoRedirect())
        request = Request(LOCAL_OAUTH_ORIGIN + path, headers={"User-Agent": "AppViewCamera-Gateway"})
        try:
            response = opener.open(request, timeout=20)
            status = int(response.status)
            headers = response.headers
            body = response.read(128 * 1024)
        except HTTPError as error:
            status = int(error.code)
            headers = error.headers
            body = error.read(128 * 1024)
        allowed_headers = {
            key: value
            for key, value in headers.items()
            if key.lower() in ("location", "content-type", "cache-control")
        }
        return {
            "status": status,
            "headers": allowed_headers,
            "body_base64": base64.b64encode(body).decode("ascii"),
        }

    @staticmethod
    def _extract_token(output: str) -> dict[str, Any]:
        decoder = json.JSONDecoder()
        for index, character in enumerate(output):
            if character != "{":
                continue
            try:
                value, _ = decoder.raw_decode(output[index:])
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict) and value.get("access_token") and value.get("token_type"):
                return value
        raise RuntimeError("Không nhận được OAuth token hợp lệ từ rclone")

    def _cleanup(self) -> None:
        cutoff = int(time.time() * 1000) - 15 * 60_000
        for session_id in list(self._sessions):
            session = self._sessions[session_id]
            if session.created_at_ms < cutoff:
                self._terminate(session)
                del self._sessions[session_id]

    @staticmethod
    def _terminate(session: OAuthSession) -> None:
        process = session.process
        if process and process.poll() is None:
            process.terminate()

    @staticmethod
    def _safe_error(output: str, fallback: str) -> str:
        lines = [
            line.strip()
            for line in output.splitlines()
            if line.strip()
            and "access_token" not in line
            and "refresh_token" not in line
        ]
        message = lines[-1] if lines else fallback
        if "access_token" in message or "refresh_token" in message:
            message = "Không lưu được thông tin đăng nhập Google"
        return message[-500:]

    @staticmethod
    def _public(session: OAuthSession) -> dict[str, Any]:
        return {
            "session_id": session.id,
            "remote_id": session.remote_id,
            "display_name": session.display_name,
            "status": session.status,
            "authorization_url": session.authorization_url,
            "error": session.error,
        }
