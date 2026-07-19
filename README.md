# AppViewCamera

Dự án chỉ gồm ba sản phẩm:

1. **Termux Gateway** — Gateway Python chạy trong Termux.
2. **AppView Android Gateway** — Gateway độc lập dạng APK.
3. **AppView Viewer** — ứng dụng xem camera và quản lý Gateway.

Hai ứng dụng Android yêu cầu **Android 10 (API 29) trở lên**.

## Cấu trúc

- `termux-gateway/`: mã nguồn, cấu hình mẫu, script cài đặt và test Termux Gateway.
- `gateway/`: mã nguồn AppView Android Gateway.
- `viewer/`: mã nguồn AppView Viewer.
- `.github/workflows/`: build/test cloud cho Termux và hai APK Android.
- Các file Gradle ở thư mục gốc là hạ tầng build bắt buộc cho hai ứng dụng Android.

## AppView Android Gateway

- HTTP API Bearer tại cổng 8080 và RTSP relay MediaMTX tại cổng 8554.
- Camera CRUD, quét LAN, kiểm tra RTSP, QR pairing và tự chạy sau reboot.
- Mật khẩu camera, Drive và YouTube token được mã hóa bằng Android Keystore.
- Recording fMP4, Room/SQLite index, retention an toàn và HTTP Range playback.
- Google Drive upload/restore và YouTube Private upload chạy nền.
- APK chứa MediaMTX ARM64 cho thiết bị thật và x86_64 cho cloud emulator.

## AppView Viewer

- Ghép nối và quản lý nhiều Gateway.
- Live grid dùng substream; xem chi tiết dùng main stream.
- Playback theo camera/ngày, bảo vệ clip và khôi phục clip từ Drive.
- Quản lý Google Drive và YouTube Private trong mục Lưu trữ.
- Token Gateway được lưu bằng Android Keystore.

## Termux Gateway

Xem hướng dẫn cài đặt tại [termux-gateway/README.md](termux-gateway/README.md).

## Build và kiểm tra

- `Android build`: unit test, lint, build hai APK, kiểm tra signer/minSdk và cài/cài đè trên Android 10 emulator.
- `Termux Gateway`: pytest, shellcheck và tạo gói Termux Gateway.

APK cloud dùng khóa debug ổn định để các bản kế tiếp có thể cài đè cùng chữ ký.
