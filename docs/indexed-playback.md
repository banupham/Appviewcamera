# Indexed playback

Gateway 1.3 xem SQLite là nguồn chỉ mục duy nhất cho Xem lại. Khi Viewer chọn camera
hoặc ngày, Gateway không liệt kê thư mục Google Drive, không tìm kiếm YouTube và không
quét lại file local.

## API

- `GET /api/playback/days?camera_id=...`
- `GET /api/playback/timeline?camera_id=...&day=YYYY-MM-DD`
- `GET /api/playback/timeline?camera_id=...&from_ms=...&to_ms=...`
- `GET /api/playback/items/{id}`
- `GET /api/playback/items/{id}/sources`
- `GET|HEAD /api/playback/items/{id}/stream?source=auto|local|drive`

`start_time`, `end_time` và `duration` dùng mili giây Unix. Mỗi clip chỉ tạo một
item timeline; `local_available`, `drive_available` và `youtube_available` là các nguồn
của cùng item.

## Thứ tự nguồn

1. `LOCAL_CACHE`
2. `DRIVE_READY`
3. `YOUTUBE_READY`

Drive được phát qua Gateway và playback cache, có HTTP Range. YouTube dùng duy nhất
`youtube_video_id` và `youtube_start_offset_seconds` từ SQLite. URL xem YouTube không
chứa OAuth token; video Private có thể yêu cầu người dùng đăng nhập đúng tài khoản.

## Migration YouTube

Migration chỉ thêm cột, không xóa record cũ:

- `youtube_state`: `NOT_CONFIGURED`, `PENDING`, `PROCESSING`, `YOUTUBE_READY`, `FAILED`.
- `youtube_video_id`.
- `youtube_start_offset_seconds`.
- `youtube_updated_at_ms` và `youtube_last_error`.

Worker YouTube tự động có thể cập nhật các cột này qua
`GatewayDatabase.set_youtube_source`. Playback không chờ YouTube: Drive vẫn xem được
trong khi `youtube_state=PROCESSING`.
