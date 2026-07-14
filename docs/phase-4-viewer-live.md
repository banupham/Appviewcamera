# Giai đoạn 4 — Viewer quản trị Gateway và xem live qua LAN

## Phạm vi

- Viewer lưu IP Gateway, API port, RTSP port và API token.
- API token được mã hóa bằng Android Keystore.
- Tab Thiết bị gọi Gateway để quét LAN, xem candidate, thêm/sửa/xóa camera.
- Password camera chỉ gửi tới Gateway khi lưu và không được API trả về.
- Tab Trực tiếp lấy relay path từ Gateway và phát `rtsp://GATEWAY:8554/path` bằng Media3.
- RTSP bắt buộc RTP-over-TCP, buffer thấp và tự kết nối lại 1/3/5/10 giây.
- Không transcoding trong Viewer hoặc Gateway.

## Cấu hình thử trong LAN

Gateway đã kiểm thử có LAN IP `192.168.1.2`, API `8080` và RTSP `8554`. IP không được hard-code
trong ứng dụng; người dùng nhập tại tab Cài đặt. API token chỉ lấy một lần khi ghép nối và được giữ
trong Keystore của Viewer.

## Chưa thuộc giai đoạn này

- Playback clip và calendar.
- Google Drive OAuth/multi-account.
- Tailscale pairing tự động; IP Tailscale vẫn được nhập như IP thông thường ở Giai đoạn 5.
