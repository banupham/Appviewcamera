# Ghi hình và xem lại — Gateway 0.8 / Viewer 0.9

## Luồng dữ liệu

```text
Camera substream (ưu tiên) -> MediaMTX fMP4 segment -> recordings/<camera>/<date>/
                                                        |
                                                        +-> SQLite recording_clips
                                                        +-> upload queue / nhiều Google Drive
                                                        +-> REST API có HTTP Range
                                                        +-> Viewer Media3 playback local hoặc Drive cache
```

MediaMTX ghi nguyên codec, không transcoding. Ghi hình mặc định **tắt** để bản cập nhật không tự sử dụng dung lượng.
Người dùng bật/tắt từ tab `Xem lại` của Viewer.

## Cấu hình an toàn mặc định

- Segment hoàn tất mỗi 60 giây; part fMP4 1 giây.
- Ưu tiên `sub_path`, fallback `main_path`.
- MediaMTX không tự xóa segment. Retention chỉ xóa bản local sau khi upload và xác minh kích thước thành công.
- Clip chuyển động hoặc clip người dùng bảo vệ không bị retention tự động xóa.
- Chỉ camera có `record_enabled=true` được ghi.
- Config và clip nằm dưới `~/appviewcamera`; đường dẫn thoát khỏi app home bị từ chối.

## API

- `GET /api/recording`: trạng thái, retention, số clip và dung lượng trống.
- `PUT /api/recording`: bật/tắt và đổi retention.
- `GET /api/recordings?camera_id=...&from_ms=...&to_ms=...`: đọc SQLite theo camera/ngày, không quét Drive.
- `GET|HEAD /api/recordings/{id}/content`: phát MP4 có Bearer token, hỗ trợ byte range để tua.
- `PUT /api/recordings/{id}/protection`: bảo vệ hoặc bỏ bảo vệ clip.
- `GET /api/storage/summary`: quota tổng, bitrate thực, dung lượng/ngày và thời gian lưu ước tính.

## Kiểm thử thiết bị

1. Cập nhật Gateway và Viewer.
2. Tab `Xem lại` -> `Bật ghi hình`.
3. Chờ tối thiểu 70 giây.
4. Bấm `Làm mới clip`, chọn camera và `Phát clip`.
5. Xác nhận có thể pause/seek và clip mới xuất hiện mỗi phút.

Tài liệu cấu hình recording chính thức của MediaMTX:
<https://github.com/bluenviron/mediamtx/blob/main/mediamtx.yml>
