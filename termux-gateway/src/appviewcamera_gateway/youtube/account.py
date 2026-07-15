from __future__ import annotations

import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from ..config import _atomic_json, _read_json
from .index import YouTubeRepository


YOUTUBE_UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload"


@dataclass(frozen=True)
class YouTubeConfig:
    enabled: bool
    client_id: str
    client_secret: str
    target_duration_minutes: int
    upload_limit_per_day: int
    max_target_uploads_per_day: int
    retry_seconds: tuple[int, ...]
    chunk_bytes: int
    batch_root: Path

    @classmethod
    def load(cls, home: Path) -> "YouTubeConfig":
        raw = _read_json(home / "config" / "youtube.json", {})
        section = raw.get("youtube", {}) if isinstance(raw, dict) else {}
        requested = int(section.get("target_youtube_duration_minutes", 60))
        if requested not in (15, 30, 60, 120):
            requested = 60
        retry = tuple(
            max(10, int(value))
            for value in section.get("retry_seconds", [60, 300, 900, 3600, 21600])
            if isinstance(value, (int, float))
        )
        root = (home / str(section.get("batch_root", "youtube/batches"))).resolve()
        if home.resolve() not in root.parents:
            raise ValueError("youtube batch_root must stay inside APPVIEWCAMERA_HOME")
        return cls(
            enabled=bool(section.get("enabled", False)),
            client_id=str(section.get("client_id", "")).strip(),
            client_secret=str(section.get("client_secret", "")).strip(),
            target_duration_minutes=requested,
            upload_limit_per_day=max(1, int(section.get("upload_limit_per_day", 100))),
            max_target_uploads_per_day=max(1, min(100, int(section.get("max_target_uploads_per_day", 80)))),
            retry_seconds=retry or (60, 300, 900, 3600),
            chunk_bytes=max(256 * 1024, int(section.get("resumable_chunk_bytes", 8 * 1024 * 1024))),
            batch_root=root,
        )


class YouTubeAccountStore:
    def __init__(
        self,
        home: Path,
        repository: YouTubeRepository,
        requester: Callable[..., Any] = urlopen,
    ):
        self.home = home
        self.repository = repository
        self.requester = requester

    def config(self) -> YouTubeConfig:
        return YouTubeConfig.load(self.home)

    def list(self) -> list[dict[str, Any]]:
        return self.repository.list_accounts()

    def delete(self, account_id: str) -> bool:
        return self.repository.delete_account(account_id)

    def configure(
        self, client_id: str, client_secret: str, target_duration_minutes: int = 60
    ) -> dict[str, Any]:
        if target_duration_minutes not in (15, 30, 60, 120):
            raise ValueError("YouTube target duration must be 15, 30, 60 or 120 minutes")
        path = self.home / "config" / "youtube.json"
        raw = _read_json(path, {})
        section = dict(raw.get("youtube", {})) if isinstance(raw, dict) else {}
        effective_client_id = client_id.strip() or str(section.get("client_id", "")).strip()
        effective_client_secret = client_secret.strip() or str(section.get("client_secret", "")).strip()
        if not effective_client_id or not effective_client_secret:
            raise ValueError("YouTube OAuth client ID and secret are required")
        section.update({
            "enabled": True,
            "client_id": effective_client_id,
            "client_secret": effective_client_secret,
            "target_youtube_duration_minutes": target_duration_minutes,
        })
        _atomic_json(path, {"youtube": section})
        return {"configured": True, "target_duration_minutes": target_duration_minutes}

    def access_token(self, account_id: str | None = None) -> tuple[str, str]:
        account = self.repository.account(account_id)
        if not account:
            raise RuntimeError("No YouTube account is configured")
        token = dict(account["token"])
        now = int(time.time() * 1000)
        if token.get("access_token") and int(token.get("expires_at_ms") or 0) > now + 60_000:
            return str(account["id"]), str(token["access_token"])
        refresh_token = str(token.get("refresh_token") or "")
        config = self.config()
        if not refresh_token:
            raise RuntimeError("YouTube account needs reconnect")
        form = {
            "client_id": config.client_id,
            "client_secret": config.client_secret,
            "refresh_token": refresh_token,
            "grant_type": "refresh_token",
        }
        request = Request(
            "https://oauth2.googleapis.com/token",
            data=urlencode(form).encode(),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        try:
            with self.requester(request, timeout=30) as response:
                refreshed = json.loads(response.read().decode())
            token.update(refreshed)
            token["refresh_token"] = refresh_token
            token["expires_at_ms"] = now + int(token.get("expires_in", 3600)) * 1000
            self.repository.update_account_token(str(account["id"]), token)
            return str(account["id"]), str(token["access_token"])
        except Exception as error:
            self.repository.account_error(str(account["id"]), str(error))
            raise RuntimeError(f"Cannot refresh YouTube OAuth: {error}") from error
