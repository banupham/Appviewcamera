# AppViewCamera

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
- Giai đoạn 3: chuyển Gateway runtime sang Termux, tự quét LAN và relay MediaMTX — đang thực hiện.

Gateway APK cũ đang được giữ làm bản đối chiếu cho đến khi Termux Gateway được kiểm thử trên
điện thoại thật. Viewer APK tiếp tục được phát triển và build trên GitHub.

## Termux Gateway MVP

Mã nguồn nằm tại `termux-gateway/` và hiện cung cấp:

- API FastAPI có Bearer token.
- Camera CRUD; mật khẩu tách khỏi `cameras.json`.
- ONVIF WS-Discovery và quét các cổng LAN có giới hạn.
- SQLite lưu camera tìm thấy, không quét lại toàn mạng để đọc danh sách.
- Tự sinh cấu hình MediaMTX và relay RTSP không transcoding.
- Giám sát MediaMTX, retry 5, 10, 30 rồi 60 giây.
- Script cài đặt, start/stop/status/doctor và Termux:Boot.
- Cấu hình ghi hình và nhiều Google Drive đã tách file; worker ghi/upload được triển khai ở các giai đoạn sau.

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
- [Database schema](docs/database-schema.md)
- [Roadmap](docs/roadmap.md)
