# Android Gateway 0.4

Android Gateway là server trung gian độc lập chạy trực tiếp trong một APK ARM64 sideload. Nó dùng
cùng hợp đồng API với Termux Gateway nhưng hai sản phẩm thay thế cho nhau, không bổ trợ hoặc phụ
thuộc runtime của nhau.

## Phạm vi đã hoàn thiện

- Foreground service mở HTTP API `0.0.0.0:8080` và RTSP proxy `0.0.0.0:8554`.
- Token ngẫu nhiên 256-bit, Bearer authentication và so sánh token constant-time.
- QR pairing và nút sao chép URI `appviewcamera://pair?...`.
- `GET /health`, `GET /api/status`, camera CRUD và discovery API tương thích Viewer.
- Quét TCP có giới hạn trong subnet IPv4 `/24` hiện tại, trên cổng 554, 80, 8000 và 8554.
- MediaMTX ARM64 1.18.2 được tải bằng checksum cố định khi build và đóng trực tiếp trong APK.
- Main ingest dùng chung cho relay và fMP4 recording, tránh hai session cạnh tranh trên camera.
- Substream chỉ mở on-demand cho lưới Viewer; phóng to tự chuyển về main stream.
- Mật khẩu tiếp tục được lưu bằng Android Keystore; API danh sách camera không trả password.
- Partial wake lock và high-performance Wi-Fi lock trong lúc service chạy.
- Tùy chọn tự chạy sau `BOOT_COMPLETED`.
- Room recording index được migrate không phá dữ liệu camera, quét lại segment mỗi 30 giây và phục hồi sau reboot.
- Playback local hỗ trợ danh sách ngày, timeline, nguồn phát, bảo vệ clip và HTTP `Range`/`HEAD`.
- Hot cache dùng tối đa mục tiêu 1% dung lượng. Gateway không xóa bản local duy nhất chưa được xác minh trên Drive/YouTube; nếu chạm ngưỡng trước khi có bản remote, recording tự tạm dừng.

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
- `GET|PUT /api/recording`
- `GET /api/recordings`
- `PUT /api/recordings/{id}/protection`
- `GET|HEAD /api/recordings/{id}/content`
- `GET /api/playback/days`
- `GET /api/playback/timeline`
- `GET /api/playback/items/{id}`
- `GET /api/playback/items/{id}/sources`
- `GET|HEAD /api/playback/items/{id}/stream`

Recording index, local 1% hot cache và local playback đã chạy trong Android Gateway 0.4. Hai tầng
tiếp theo là nhiều tài khoản Drive và YouTube Private. Credential loại Desktop OAuth và refresh
token sẽ chỉ được nhập/lưu mã hóa trên Gateway; Viewer không nhận hoặc lưu secret.

Mục tiêu tải tự thích ứng là 4/8/16 camera 1080p tùy RAM, decoder và số instance codec phần cứng.
Không transcode main stream; lưới nhiều camera phải cấu hình substream H.264 nhẹ trên camera.

Foreground service loại `specialUse` phù hợp bản cài nội bộ/sideload. Nếu phát hành Google Play,
cần khai báo use case foreground service trong Play Console và kiểm tra lại chính sách hiện hành.
