from __future__ import annotations

import base64
import hashlib
import html
import json
import secrets
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen

from .account import YOUTUBE_UPLOAD_SCOPE, YouTubeAccountStore
from .index import YouTubeRepository


REDIRECT_URI = "http://127.0.0.1:53683/"


@dataclass
class YouTubeOAuthSession:
    id: str
    account_id: str
    display_name: str
    state: str
    verifier: str
    authorization_url: str
    created_at_ms: int
    status: str = "WAITING_BROWSER"
    error: str | None = None


class YouTubeOAuthManager:
    def __init__(
        self,
        accounts: YouTubeAccountStore,
        repository: YouTubeRepository,
        requester: Callable[..., Any] = urlopen,
    ):
        self.accounts = accounts
        self.repository = repository
        self.requester = requester
        self._lock = threading.RLock()
        self._sessions: dict[str, YouTubeOAuthSession] = {}

    def start(self, account_id: str, display_name: str) -> dict[str, Any]:
        normalized = account_id.lower().strip()
        if not normalized or any(char not in "abcdefghijklmnopqrstuvwxyz0123456789_-" for char in normalized):
            raise ValueError("YouTube account ID only allows a-z, 0-9, _ and -")
        config = self.accounts.config()
        if not config.client_id:
            raise RuntimeError("Configure youtube.client_id on Gateway before OAuth")
        verifier = secrets.token_urlsafe(64)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
        state = secrets.token_urlsafe(24)
        parameters = {
            "client_id": config.client_id,
            "redirect_uri": REDIRECT_URI,
            "response_type": "code",
            "scope": YOUTUBE_UPLOAD_SCOPE,
            "access_type": "offline",
            "prompt": "consent",
            "include_granted_scopes": "true",
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        }
        session = YouTubeOAuthSession(
            id=uuid.uuid4().hex,
            account_id=normalized,
            display_name=display_name.strip() or normalized,
            state=state,
            verifier=verifier,
            authorization_url="https://accounts.google.com/o/oauth2/v2/auth?" + urlencode(parameters),
            created_at_ms=int(time.time() * 1000),
        )
        with self._lock:
            self._cleanup()
            self._sessions[session.id] = session
        return self._public(session)

    def get(self, session_id: str) -> dict[str, Any]:
        with self._lock:
            session = self._sessions.get(session_id)
            if not session:
                raise ValueError("YouTube OAuth session expired")
            return self._public(session)

    def callback(self, session_id: str, path: str) -> dict[str, Any]:
        with self._lock:
            session = self._sessions.get(session_id)
            if not session or session.status != "WAITING_BROWSER":
                raise ValueError("YouTube OAuth session is not waiting for callback")
        parsed = urlsplit(path)
        query = parse_qs(parsed.query)
        if query.get("state", [""])[0] != session.state:
            return self._finish_error(session, "OAuth state does not match")
        if query.get("error"):
            return self._finish_error(session, str(query["error"][0]))
        code = str(query.get("code", [""])[0])
        if not code:
            return self._finish_error(session, "OAuth callback has no code")
        try:
            config = self.accounts.config()
            form = {
                "client_id": config.client_id,
                "client_secret": config.client_secret,
                "code": code,
                "code_verifier": session.verifier,
                "redirect_uri": REDIRECT_URI,
                "grant_type": "authorization_code",
            }
            request = Request(
                "https://oauth2.googleapis.com/token",
                data=urlencode(form).encode(),
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                method="POST",
            )
            with self.requester(request, timeout=30) as response:
                token = json.loads(response.read().decode())
            if not token.get("access_token") or not token.get("refresh_token"):
                raise RuntimeError("Google did not return a reusable YouTube token")
            token["expires_at_ms"] = int(time.time() * 1000) + int(token.get("expires_in", 3600)) * 1000
            self.repository.upsert_account(
                session.account_id, session.display_name, token, YOUTUBE_UPLOAD_SCOPE
            )
            session.status = "COMPLETE"
            session.error = None
            return self._browser_response("YouTube account connected. You can return to AppViewCamera.")
        except Exception as error:
            return self._finish_error(session, str(error))

    def _finish_error(self, session: YouTubeOAuthSession, error: str) -> dict[str, Any]:
        session.status = "ERROR"
        session.error = error[-500:]
        return self._browser_response(f"YouTube sign-in failed: {error}", status=400)

    def _cleanup(self) -> None:
        cutoff = int(time.time() * 1000) - 10 * 60_000
        self._sessions = {
            key: value for key, value in self._sessions.items() if value.created_at_ms >= cutoff
        }

    @staticmethod
    def _browser_response(message: str, status: int = 200) -> dict[str, Any]:
        body = (
            "<!doctype html><meta charset='utf-8'><title>AppViewCamera YouTube</title>"
            f"<p>{html.escape(message)}</p>"
        ).encode()
        return {
            "status": status,
            "headers": {"Content-Type": "text/html; charset=utf-8"},
            "body_base64": base64.b64encode(body).decode(),
        }

    @staticmethod
    def _public(session: YouTubeOAuthSession) -> dict[str, Any]:
        return {
            "session_id": session.id,
            "account_id": session.account_id,
            "display_name": session.display_name,
            "status": session.status,
            "authorization_url": session.authorization_url,
            "error": session.error,
        }
