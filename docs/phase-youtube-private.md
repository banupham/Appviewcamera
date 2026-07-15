# YouTube Private archive

Google Drive remains the primary store. The YouTube worker runs independently and only reads clips whose Drive upload was verified (`DRIVE_READY` or `LOCAL_CACHE`). A YouTube failure never changes Drive state and never stops MediaMTX recording.

## One-time OAuth application setup

Create an OAuth 2.0 **Desktop app** in the Google Cloud project that has YouTube Data API v3 enabled. On the first `Add YouTube Private` action, enter the client ID and client secret in Viewer. They are sent once to Gateway and saved in `~/appviewcamera/config/youtube.json`; Viewer does not retain them. The resulting configuration is equivalent to:

```json
{
  "youtube": {
    "enabled": true,
    "client_id": "YOUR_DESKTOP_CLIENT_ID",
    "client_secret": "YOUR_DESKTOP_CLIENT_SECRET",
    "target_youtube_duration_minutes": 60,
    "upload_limit_per_day": 100,
    "max_target_uploads_per_day": 80
  }
}
```

Accounts are then added, reconnected, and removed in Viewer > Storage. Viewer opens Google consent and proxies the loopback callback on port `53683`. It never receives OAuth access or refresh tokens.

The requested scope is only `https://www.googleapis.com/auth/youtube.upload`. Upload metadata always sets `privacyStatus=private` and `notifySubscribers=false`.

## Batching and retry

- Default batch length is 60 minutes; configured values may be 15, 30, 60, or 120 minutes.
- The quota planner may automatically choose 90 or 120 minutes to stay at or below 80 uploads/day.
- Segments are grouped by camera and local day; different cameras and days are never mixed.
- `ffprobe` checks compatibility and `ffmpeg -c copy` joins compatible MP4 segments without default transcoding.
- The resumable URL and byte offset are stored in SQLite. The same session resumes after network loss/reboot, while one unique job per batch prevents duplicates.
- Original Drive/local segments are never removed by the YouTube worker.

SQLite tables: `youtube_accounts`, `youtube_batches`, `youtube_upload_jobs`. Clip mappings store video ID, batch ID, start/end offsets, upload time, and processing state.

Automated tests mock HTTP. They do not upload/delete real YouTube videos or delete real Google Drive files.
