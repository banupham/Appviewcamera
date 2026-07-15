# Drive-primary recording migration

Gateway 1.2 chuyển Google Drive thành kho video chính. Thư mục `recordings/` chỉ còn là staging,
hàng đợi retry và cache ngắn hạn. OAuth, nhiều tài khoản Drive, chuyển Drive, retention và clip
protection không đổi.

## State machine

`recording_clips.clip_state` dùng các trạng thái:

- `RECORDING`: MediaMTX còn ghi segment.
- `LOCAL_PENDING`: segment hoàn tất và đang chờ upload.
- `DRIVE_UPLOADING`: rclone đang upload.
- `LOCAL_CACHE`: Drive đã được xác minh; bản local đang được giữ tạm.
- `DRIVE_READY`: Drive đã được xác minh; staging local đã được dọn.
- `UPLOAD_RETRY`: upload lỗi/mất Internet; file local bắt buộc được giữ.
- `FAILED`: không thể tiếp tục tự động, ví dụ staging biến mất trước khi Drive được xác minh.

Hai cột cũ `local_state` và `upload_state` vẫn được cập nhật để Viewer/API cũ tương thích.

## SQLite migration

Migration chạy tự động, trong transaction khi Gateway mở database. Không drop bảng và không xóa row.
Các cột được thêm:

- `clip_state`, `state_updated_at_ms`;
- `remote_file_id`, `remote_size_bytes`, `remote_verified_at_ms`;
- `local_cached_at_ms`, `local_deleted_at_ms`.

Ánh xạ record cũ:

| Trạng thái cũ | Trạng thái mới |
|---|---|
| `UPLOADED` + local `AVAILABLE` | `LOCAL_CACHE` |
| `UPLOADED` + local missing | `DRIVE_READY` |
| `UPLOADING` | `DRIVE_UPLOADING` |
| `FAILED` + local `AVAILABLE` | `UPLOAD_RETRY` |
| `PENDING` + local `AVAILABLE` | `LOCAL_PENDING` |
| local missing trước upload | `FAILED` |

Upload ở phiên bản cũ đã kiểm tra kích thước trước khi ghi `UPLOADED`, nên migration điền
`remote_size_bytes=size_bytes` và `remote_verified_at_ms=uploaded_at_ms`. Vì file ID thật không có
trong schema cũ, `remote_file_id` được đánh dấu `legacy-path:<remote_path>`. Upload mới luôn lưu ID
thật do Google Drive trả qua `rclone lsjson`.

## Cleanup local an toàn

- Mặc định giữ `LOCAL_CACHE` 360 phút (6 giờ).
- Khi dung lượng trống dưới 2 GiB hoặc filesystem đã dùng từ 85%, dọn `LOCAL_CACHE` cũ nhất.
- Chỉ xóa khi có `remote_verified_at_ms` và `remote_size_bytes == size_bytes`.
- Không chọn `RECORDING`, `LOCAL_PENDING`, `DRIVE_UPLOADING`, `UPLOAD_RETRY` hoặc `FAILED`.
- Clip protected vẫn được bảo vệ khỏi Drive retention; bản local đã xác minh vẫn chỉ là cache.

Playback luôn tra đúng row SQLite. Nếu staging/cache local còn thì dùng local; nếu không, Gateway tải
đúng `remote_id + remote_path` vào playback cache rồi phục vụ HTTP Range. Gateway không duyệt toàn bộ Drive.
