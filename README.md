# AppViewCamera

Hệ thống camera Android gồm hai ứng dụng được xây dựng mới hoàn toàn:

- **Gateway:** chạy trên điện thoại Android đặt cùng LAN với camera IP.
- **Viewer:** kết nối tới Gateway qua mạng riêng Tailscale để xem trực tiếp, xem lại và quản lý lưu trữ.

Mục tiêu kiến trúc là không mở NAT, không port forwarding và không công khai camera trực tiếp ra Internet. Tailscale được cài độc lập; ứng dụng chỉ dùng IP Tailscale như địa chỉ mạng bình thường.

## Trạng thái hiện tại

Giai đoạn 2 đã bổ sung quản lý camera trong Gateway và kiểm tra RTSP thật bằng Media3. Gateway lưu cấu hình bằng Room, mã hóa mật khẩu với Android Keystore và hiển thị codec/độ phân giải/FPS/bitrate nếu camera cung cấp. Chưa tuyên bố RTSP relay, ghi hình, motion detection, REST API hoặc Google Drive hoạt động.

```text
Appviewcamera/
├── gateway/    APK chạy tại nơi đặt camera
├── viewer/     APK chạy trên điện thoại người dùng
├── docs/       Tài liệu theo từng giai đoạn
└── .github/    Quy trình test và build APK
```

## Build không cần Android Studio

Yêu cầu JDK 17 và Android SDK Command-line Tools với Android API 35.

Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat :gateway:assembleDebug :viewer:assembleDebug
```

Linux/macOS:

```bash
./gradlew test
./gradlew :gateway:assembleDebug :viewer:assembleDebug
```

Kết quả:

```text
gateway/build/outputs/apk/debug/gateway-debug.apk
viewer/build/outputs/apk/debug/viewer-debug.apk
```

GitHub Actions chạy cùng các lệnh và tải hai APK thành artifacts `gateway-debug.apk` và `viewer-debug.apk`.

## Tailscale

1. Cài Tailscale trên cả Gateway và Viewer.
2. Đăng nhập hai thiết bị vào cùng một tailnet.
3. Bật kết nối Tailscale.
4. Xem IP `100.x.x.x` của Gateway trong ứng dụng Tailscale; thiết bị có CLI có thể chạy `tailscale ip -4`.
5. Không mở port trên router. Các giai đoạn sau sẽ cho Viewer cấu hình IP này, không hard-code trong source.

Xem [báo cáo Giai đoạn 1](docs/phase-1.md), [báo cáo Giai đoạn 2](docs/phase-2.md), [database schema](docs/database-schema.md) và [kế hoạch triển khai](docs/roadmap.md).
