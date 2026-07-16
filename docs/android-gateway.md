# Android Gateway 0.2

Android Gateway là server trung gian gọn nhẹ chạy trực tiếp trong một APK. Nó dùng cùng pairing
URI và các endpoint camera chính với Termux Gateway, vì vậy Viewer hiện tại có thể ghép nối mà
không cần cấu hình riêng.

## Phạm vi đã hoàn thiện

- Foreground service mở HTTP API `0.0.0.0:8080` và RTSP proxy `0.0.0.0:8554`.
- Token ngẫu nhiên 256-bit, Bearer authentication và so sánh token constant-time.
- QR pairing và nút sao chép URI `appviewcamera://pair?...`.
- `GET /health`, `GET /api/status`, camera CRUD và discovery API tương thích Viewer.
- Quét TCP có giới hạn trong subnet IPv4 `/24` hiện tại, trên cổng 554, 80, 8000 và 8554.
- RTSP proxy theo relay path, không transcode, ưu tiên RTSP interleaved/TCP từ Viewer.
- Proxy tự đăng nhập upstream bằng Basic hoặc Digest MD5/MD5-sess; mật khẩu camera không đi tới Viewer.
- Mật khẩu tiếp tục được lưu bằng Android Keystore; API danh sách camera không trả password.
- Partial wake lock và high-performance Wi-Fi lock trong lúc service chạy.
- Tùy chọn tự chạy sau `BOOT_COMPLETED`.

## Cách thử

1. Cài `gateway-debug.apk` lên điện thoại nằm cùng LAN với camera.
2. Mở app, thêm camera và bấm **Test RTSP** để xác nhận URL/tài khoản.
3. Bấm **Khởi động** trong thẻ **Server trung gian**.
4. Trên Viewer, quét QR hiển thị trong Android Gateway hoặc sao chép chuỗi pairing.
5. Trong Viewer, tải danh sách camera và mở relay `rtsp://IP_GATEWAY:8554/relay_path`.

Khi dùng qua Tailscale, pairing URI cần chứa IP Tailscale. Nếu máy có đồng thời Wi-Fi/VPN, app
hiển thị các địa chỉ IPv4 để chọn trước khi quét QR.

## API

Không cần token cho `GET /health`. Mọi endpoint `/api/*` yêu cầu:

```text
Authorization: Bearer <token>
```

Các endpoint hoạt động:

- `GET /api/status`
- `GET /api/cameras`
- `PUT /api/cameras/{id}` (`id` phải trùng `relay_path`)
- `DELETE /api/cameras/{id}`
- `GET /api/discovery/candidates`
- `POST /api/discovery/scan`

Endpoint recording/playback/storage/YouTube trả HTTP 501 với thông báo rõ ràng. Viewer không nên
hiển thị chúng như một capability khả dụng khi `capabilities` trong `/api/status` là `false`.

## Giới hạn có chủ đích

Android Gateway 0.2 chưa ghi hình, chưa upload Drive/YouTube và chưa phục vụ playback. Media3 là
RTSP client/player, không phải media relay server. Bản này dùng RTSP application proxy riêng cho
live view; Termux Gateway + MediaMTX vẫn là cấu hình đầy đủ cho recording và lưu trữ.

RTSP proxy cần kiểm thử thiết bị thật với từng hãng camera. Nó hỗ trợ luồng xem RTSP/TCP và auth
phổ biến; các camera chỉ cho RTP/UDP, yêu cầu TLS riêng, hoặc cơ chế auth độc quyền có thể không
tương thích.

Foreground service loại `specialUse` phù hợp bản cài nội bộ/sideload. Nếu phát hành Google Play,
cần khai báo use case foreground service trong Play Console và kiểm tra lại chính sách hiện hành.
