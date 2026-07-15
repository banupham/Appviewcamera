# Database schema

Database Gateway: `camera_gateway.db` (Room, schema version 1).

## `cameras`

| Cột | Kiểu | Nội dung |
|---|---|---|
| `id` | INTEGER PK | ID tự tăng |
| `name` | TEXT | Tên camera |
| `ip` | TEXT | IP hoặc hostname trong LAN |
| `port` | INTEGER | Cổng RTSP |
| `username` | TEXT | Username; không ghi vào log |
| `encryptedPassword` | TEXT | AES-GCM ciphertext từ Android Keystore |
| `mainRtspUrl` | TEXT | URL không chứa userinfo |
| `subRtspUrl` | TEXT | URL phụ không chứa userinfo |
| `relayPath` | TEXT UNIQUE | Path dành cho relay |
| `enabled` | INTEGER/BOOL | Camera được bật |
| `recordEnabled` | INTEGER/BOOL | Cho phép ghi |
| `motionEnabled` | INTEGER/BOOL | Cho phép motion detection |
| `audioEnabled` | INTEGER/BOOL | Cho phép audio |
| `connectionStatus` | TEXT | OFFLINE/CONNECTING/RECONNECTING/CONNECTED |
| `codec` | TEXT NULL | Codec/MIME từ Media3 |
| `width`, `height` | INTEGER NULL | Độ phân giải |
| `fps` | REAL NULL | Frame rate |
| `bitrate` | INTEGER NULL | Bitrate bps từ stream |
| `lastError` | TEXT NULL | Lỗi đã che credential |
| `lastTestedAt` | INTEGER NULL | Unix epoch milliseconds |
| `createdAt` | INTEGER | Unix epoch milliseconds |

Index: unique `relayPath`, `enabled`, `createdAt`.

## SQLite của Termux Gateway

Database vận hành là `~/appviewcamera/data/camera_gateway.db`, bật WAL và tự migrate khi cập nhật.

- `discovery_candidates`: thiết bị/cổng tìm thấy trong LAN.
- `recording_clips`: thời gian, kích thước, `clip_state`, staging/cache local, Drive remote/file ID,
  trạng thái/video ID/offset YouTube,
  kích thước và thời gian xác minh, motion và protection. Xem migration tại
  [drive-primary-migration.md](drive-primary-migration.md).
- `motion_events`: sự kiện ISAPI đang chờ ghép với segment fMP4.
- `deletion_history`: lịch sử clip bị retention xóa, gồm lý do, Drive, kích thước và thời điểm.

- `youtube_accounts`: tài khoản và OAuth token YouTube chỉ tại Gateway; API chỉ trả metadata công khai.
- `youtube_batches`: batch theo camera/ngày, tên, khoảng thời gian, file stream-copy và trạng thái.
- `youtube_upload_jobs`: upload resumable duy nhất cho mỗi batch, session URL, byte đã gửi, retry và video ID.

Migration Gateway 1.5.0 dùng `CREATE TABLE IF NOT EXISTS`, đồng thời thêm `youtube_status`, `youtube_batch_id`,
end offset, upload time và processing status vào `recording_clips`. Record cũ không bị xóa; `youtube_state`
cũ được đồng bộ sang `youtube_status` khi cần.

Index chính: `(camera_id, started_at_ms DESC)`, `relative_path UNIQUE`, và `(processed, detected_at_ms)` cho motion. Viewer luôn tìm clip theo SQLite; không quét Google Drive khi chọn ngày.
