from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .account import YouTubeAccountStore
from .index import YouTubeRepository


UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/youtube/v3/videos"


class YouTubeResumableUploader:
    def __init__(
        self,
        repository: YouTubeRepository,
        accounts: YouTubeAccountStore,
        requester: Callable[..., Any] = urlopen,
    ):
        self.repository = repository
        self.accounts = accounts
        self.requester = requester

    def upload(self, job: dict[str, Any]) -> str:
        path = Path(str(job["local_path"]))
        if not path.is_file():
            raise RuntimeError("YouTube batch file is missing")
        total = path.stat().st_size
        if total <= 0:
            raise RuntimeError("YouTube batch file is empty")
        account_id, access_token = self.accounts.access_token(job.get("account_id"))
        self.repository.mark_uploading(str(job["id"]), account_id)
        upload_url = str(job.get("upload_url") or "")
        uploaded = max(0, min(total, int(job.get("uploaded_bytes") or 0)))
        if upload_url:
            status, headers, payload = self._request(
                Request(
                    upload_url,
                    data=b"",
                    headers={
                        "Authorization": f"Bearer {access_token}",
                        "Content-Length": "0",
                        "Content-Range": f"bytes */{total}",
                    },
                    method="PUT",
                )
            )
            if status in (200, 201):
                video_id = str(payload.get("id") or "")
                if not video_id:
                    raise RuntimeError("YouTube completed upload without a video ID")
                self.repository.mark_uploaded(str(job["id"]), video_id)
                return video_id
            if status == 308:
                uploaded = self._uploaded_from_range(headers.get("Range"), uploaded)
                self.repository.save_upload_progress(str(job["id"]), uploaded)
            elif status in (404, 410):
                upload_url, uploaded = "", 0
                self.repository.clear_upload_session(str(job["id"]))
            else:
                raise RuntimeError(self._error(status, payload))
        if not upload_url:
            upload_url = self._start_session(job, total, access_token)
            self.repository.save_upload_session(str(job["id"]), upload_url)
        chunk_size = self.accounts.config().chunk_bytes
        with path.open("rb") as handle:
            handle.seek(uploaded)
            while uploaded < total:
                chunk = handle.read(min(chunk_size, total - uploaded))
                if not chunk:
                    raise RuntimeError("Unexpected end of YouTube batch file")
                end = uploaded + len(chunk) - 1
                status, headers, payload = self._request(
                    Request(
                        upload_url,
                        data=chunk,
                        headers={
                            "Authorization": f"Bearer {access_token}",
                            "Content-Type": "video/mp4",
                            "Content-Length": str(len(chunk)),
                            "Content-Range": f"bytes {uploaded}-{end}/{total}",
                        },
                        method="PUT",
                    )
                )
                if status == 308:
                    uploaded = self._uploaded_from_range(headers.get("Range"), end + 1)
                    self.repository.save_upload_progress(str(job["id"]), uploaded)
                    continue
                if status in (200, 201):
                    video_id = str(payload.get("id") or "")
                    if not video_id:
                        raise RuntimeError("YouTube completed upload without a video ID")
                    self.repository.mark_uploaded(str(job["id"]), video_id)
                    return video_id
                raise RuntimeError(self._error(status, payload))
        raise RuntimeError("YouTube resumable upload did not complete")

    def _start_session(self, job: dict[str, Any], total: int, access_token: str) -> str:
        url = UPLOAD_ENDPOINT + "?" + urlencode(
            {"uploadType": "resumable", "part": "snippet,status", "notifySubscribers": "false"}
        )
        metadata = json.dumps(
            {
                "snippet": {
                    "title": str(job["title"])[:100],
                    "description": str(job["description"])[:5000],
                    "categoryId": "22",
                },
                "status": {"privacyStatus": "private", "selfDeclaredMadeForKids": False},
            }
        ).encode()
        status, headers, payload = self._request(
            Request(
                url,
                data=metadata,
                headers={
                    "Authorization": f"Bearer {access_token}",
                    "Content-Type": "application/json; charset=UTF-8",
                    "Content-Length": str(len(metadata)),
                    "X-Upload-Content-Length": str(total),
                    "X-Upload-Content-Type": "video/mp4",
                },
                method="POST",
            )
        )
        location = headers.get("Location")
        if status not in (200, 201) or not location:
            raise RuntimeError(self._error(status, payload, "YouTube did not create an upload session"))
        return location

    def _request(self, request: Request) -> tuple[int, dict[str, str], dict[str, Any]]:
        try:
            with self.requester(request, timeout=120) as response:
                raw = response.read()
                return int(response.status), dict(response.headers), self._json(raw)
        except HTTPError as error:
            return int(error.code), dict(error.headers), self._json(error.read())

    @staticmethod
    def _json(raw: bytes) -> dict[str, Any]:
        if not raw:
            return {}
        try:
            value = json.loads(raw.decode())
            return value if isinstance(value, dict) else {}
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {}

    @staticmethod
    def _uploaded_from_range(value: str | None, fallback: int) -> int:
        if not value or "-" not in value:
            return fallback
        try:
            return int(value.rsplit("-", 1)[1]) + 1
        except ValueError:
            return fallback

    @staticmethod
    def _error(status: int, payload: dict[str, Any], fallback: str = "YouTube upload failed") -> str:
        detail = payload.get("error", payload)
        return f"{fallback} (HTTP {status}): {detail}"
