from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from .account import YouTubeAccountStore
from .batch import YouTubeBatchBuilder
from .index import YouTubeRepository
from .processing import YouTubeProcessingMonitor
from .retry import retry_delay_seconds
from .uploader import YouTubeResumableUploader


LOGGER = logging.getLogger("appviewcamera.youtube")


class YouTubeArchiveWorker:
    def __init__(
        self,
        repository: YouTubeRepository,
        accounts: YouTubeAccountStore,
        batch_builder: YouTubeBatchBuilder,
        uploader: YouTubeResumableUploader,
        processing: YouTubeProcessingMonitor,
        interval_seconds: int = 30,
    ):
        self.repository = repository
        self.accounts = accounts
        self.batch_builder = batch_builder
        self.uploader = uploader
        self.processing = processing
        self.interval_seconds = interval_seconds
        self.task: asyncio.Task | None = None
        self.last_error: str | None = None

    def start(self) -> None:
        if self.task and not self.task.done():
            return
        self.repository.reset_interrupted()
        self.task = asyncio.create_task(self._loop(), name="youtube-archive")

    async def stop(self) -> None:
        if not self.task:
            return
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)
        self.task = None

    async def _loop(self) -> None:
        while True:
            try:
                await asyncio.to_thread(self.run_once)
                self.last_error = None
            except asyncio.CancelledError:
                raise
            except Exception as error:
                self.last_error = str(error)[-500:]
                LOGGER.exception("YouTube archive cycle failed; Drive recording continues")
            await asyncio.sleep(self.interval_seconds)

    def run_once(self) -> None:
        config = self.accounts.config()
        if not config.enabled or not self.accounts.list():
            return
        self.batch_builder.build_next()
        job = self.repository.next_upload_job(int(time.time() * 1000))
        if job:
            try:
                self.uploader.upload(job)
            except Exception as error:
                delay = retry_delay_seconds(int(job.get("attempts") or 0) + 1, config.retry_seconds)
                self.repository.mark_retry(
                    str(job["id"]), str(error), int(time.time() * 1000) + delay * 1000
                )
                self.last_error = str(error)[-500:]
        processing = self.repository.processing_job()
        if processing:
            try:
                self.processing.check(processing)
            except Exception as error:
                self.last_error = str(error)[-500:]

    def status(self) -> dict[str, Any]:
        config = self.accounts.config()
        policy = self.batch_builder.policy()
        return {
            "enabled": config.enabled,
            "oauth_configured": bool(config.client_id and config.client_secret),
            "account_count": len(self.accounts.list()),
            "target_duration_minutes": policy["target_minutes"],
            "estimated_uploads_per_day": policy["estimated_uploads_per_day"],
            "max_target_uploads_per_day": policy["safe_upload_limit"],
            "warning": policy["warning"],
            "jobs": self.repository.status_counts(),
            "last_error": self.last_error,
        }
