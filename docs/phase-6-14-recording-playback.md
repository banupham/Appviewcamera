# Ghi hình và xem lại — Gateway 0.5 / Viewer 0.6

## Luồng dữ liệu

```text
Camera substream (ưu tiên) -> MediaMTX fMP4 segment -> recordings/<camera>/<date>/
                                                        |
                                                        +-> SQLite recording_clips
                                                        +-> REST API có HTTP Range
                                                        +-> Viewer Media3 playback
```

MediaMTX ghi nguyên codec, không transcoding. Ghi hình mặc định **tắt** để bản cập nhật không tự sử dụng dung lượng.
Người dùng bật/tắt từ tab `Xem lại` của Viewer.

## Cấu hình an toàn mặc định

- Segment hoàn tất mỗi 60 giây; part fMP4 1 giây.
- Ưu tiên `sub_path`, fallback `main_path`.
- Tự xóa segment cục bộ sau 60 phút.
- Chỉ camera có `record_enabled=true` được ghi.
- Config và clip nằm dưới `~/appviewcamera`; đường dẫn thoát khỏi app home bị từ chối.

## API

- `GET /api/recording`: trạng thái, retention, số clip và dung lượng trống.
- `PUT /api/recording`: bật/tắt và đổi retention.
- `GET /api/recordings?camera_id=...`: quét segment hoàn tất, cập nhật SQLite và trả clip mới nhất.
- `GET|HEAD /api/recordings/{id}/content`: phát MP4 có Bearer token, hỗ trợ byte range để tua.

## Kiểm thử thiết bị

1. Cập nhật Gateway và Viewer.
2. Tab `Xem lại` -> `Bật ghi hình`.
3. Chờ tối thiểu 70 giây.
4. Bấm `Làm mới clip`, chọn camera và `Phát clip`.
5. Xác nhận có thể pause/seek và clip mới xuất hiện mỗi phút.

Tài liệu cấu hình recording chính thức của MediaMTX:
<https://github.com/bluenviron/mediamtx/blob/main/mediamtx.yml>
