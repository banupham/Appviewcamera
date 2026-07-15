from __future__ import annotations

import json
from typing import Any, Callable
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .account import YouTubeAccountStore
from .index import YouTubeRepository


class YouTubeProcessingMonitor:
    def __init__(
        self,
        repository: YouTubeRepository,
        accounts: YouTubeAccountStore,
        requester: Callable[..., Any] = urlopen,
    ):
        self.repository = repository
        self.accounts = accounts
        self.requester = requester

    def check(self, job: dict[str, Any]) -> str:
        _, access_token = self.accounts.access_token(job.get("account_id"))
        video_id = str(job.get("youtube_video_id") or "")
        if not video_id:
            raise RuntimeError("YouTube processing job has no video ID")
        url = "https://www.googleapis.com/youtube/v3/videos?" + urlencode(
            {"part": "status", "id": video_id}
        )
        request = Request(url, headers={"Authorization": f"Bearer {access_token}"})
        with self.requester(request, timeout=30) as response:
            payload = json.loads(response.read().decode())
        items = payload.get("items", [])
        if not items:
            raise RuntimeError("YouTube video is not visible to the connected account")
        status = str(items[0].get("status", {}).get("uploadStatus") or "uploaded")
        if status == "processed":
            self.repository.mark_ready(str(job["id"]), status)
        elif status in ("failed", "rejected", "deleted"):
            self.repository.mark_failed(str(job["batch_id"]), f"YouTube processing {status}")
        return status
