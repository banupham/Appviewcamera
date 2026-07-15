# AppViewCamera

Phiên bản hiện tại: Viewer 1.1.1 / Gateway 1.1.2.

Hệ thống camera không mở NAT và không port forwarding. Điện thoại đặt cùng camera chạy
**Termux Gateway**, còn điện thoại người dùng chạy **Viewer APK**. Hai thiết bị kết nối qua
Tailscale như một mạng IP riêng.

```text
Camera IP --RTSP/LAN--> Termux Gateway --RTSP/API/Tailscale--> Viewer APK
                              |
                              +-- SQLite / recordings / rclone / Google Drive
```

## Trạng thái

- Giai đoạn 1: project Android và hai APK tối thiểu — hoàn thành.
- Giai đoạn 2: quản lý camera và kiểm tra RTSP trong Gateway APK thử nghiệm — hoàn thành.
- Giai đoạn 3: Termux Gateway, tự quét LAN và relay MediaMTX — hoàn thành trên thiết bị ARM64.
- Giai đoạn 4: Viewer quản trị camera và xem RTSP relay qua LAN/Tailscale — hoàn thành, đã thử H.264 1080p30.
- Giai đoạn lưu trữ: upload queue bền vững, nhiều Drive, quota thật, tự chuyển Drive và retention 90% → 80% — đã triển khai.
- Giai đoạn ghi/xem lại: fMP4, SQLite, chọn ngày, phát local/Drive, bảo vệ clip và motion ISAPI — đã triển khai.

Gateway APK cũ đang được giữ làm bản đối chiếu cho đến khi Termux Gateway được kiểm thử trên
điện thoại thật. Viewer APK tiếp tục được phát triển và build trên GitHub.

## Termux Gateway MVP

Mã nguồn nằm tại `termux-gateway/` và hiện cung cấp:

- API Python tiêu chuẩn có Bearer token, không cần pip/Rust trên Termux.
- Camera CRUD; mật khẩu tách khỏi `cameras.json`.
- ONVIF WS-Discovery và quét các cổng LAN có giới hạn.
- SQLite lưu camera tìm thấy, không quét lại toàn mạng để đọc danh sách.
- Tự sinh cấu hình MediaMTX và relay RTSP không transcoding.
- Quản lý nhiều Google Drive, upload/retry/xác minh bằng rclone và không trả OAuth token về Viewer.
- Đăng nhập Google trực tiếp từ Viewer; Gateway tự chạy phiên OAuth và lưu token, không cần mở Termux.
- Ghi segment fMP4 không transcoding, lập chỉ mục SQLite và phục vụ playback có Bearer token/HTTP Range.
- Giám sát MediaMTX, retry 5, 10, 30 rồi 60 giây.
- Script cài đặt, start/stop/status/doctor và Termux:Boot.
- Tự phát hiện sự kiện chuyển động Hikvision ISAPI, bảo vệ clip liên quan và không giải mã video liên tục.
- Ước tính bitrate, dung lượng mỗi ngày và thời gian lưu còn lại từ dữ liệu thật.
- Log rotation, Termux:Boot, watchdog MediaMTX và phục hồi upload sau khi mất mạng/reboot.
- Viewer hiển thị trạng thái Gateway ngoại tuyến và cho phép thử lại, không thoát ứng dụng.

Hướng dẫn cài và thử trên điện thoại: [termux-gateway/README.md](termux-gateway/README.md).

## Build trên GitHub

Hai workflow độc lập:

- `Android build`: test Kotlin và tạo `gateway-debug.apk`, `viewer-debug.apk`.
- `Termux Gateway`: test Python/shell và tạo `termux-gateway.zip`.

Máy hiện tại không cần Android Studio hoặc Android SDK. Kết quả chính thức được xác nhận bằng
GitHub Actions.

## Tài liệu

- [Giai đoạn 1](docs/phase-1.md)
- [Giai đoạn 2](docs/phase-2.md)
- [Giai đoạn 3](docs/phase-3-termux.md)
- [Giai đoạn 4](docs/phase-4-viewer-live.md)
- [Lưu trữ Google Drive](docs/phase-9-google-drive.md)
- [Ghi hình và xem lại](docs/phase-6-14-recording-playback.md)
- [Database schema](docs/database-schema.md)
- [Roadmap](docs/roadmap.md)
