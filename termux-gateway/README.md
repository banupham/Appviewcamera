# Termux Gateway

## Yêu cầu điện thoại

1. Cài Termux và Termux:Boot từ cùng một nguồn, khuyến nghị F-Droid.
2. Mở Termux:Boot một lần sau khi cài.
3. Tắt tối ưu pin cho Termux, Termux:Boot và Tailscale.
4. Bật Wi-Fi cùng LAN với camera và bật Tailscale.

Không dùng bản Termux cũ trên Google Play.

## Cài đặt

Giải nén artifact `termux-gateway.zip`, mở Termux tại thư mục đã giải nén rồi chạy:

```bash
chmod +x termux-gateway/scripts/*.sh
termux-gateway/scripts/install.sh
~/appviewcamera/scripts/doctor.sh
~/appviewcamera/scripts/start.sh
```

Installer cài Python, FFmpeg, rclone, tải MediaMTX ARM phù hợp, kiểm tra SHA-256, tạo API token
và tạo `~/.termux/boot/20-appviewcamera-gateway`. Gateway chỉ dùng thư viện chuẩn Python trên
điện thoại, không chạy `pip install` và không cần Rust/maturin.

Xem trạng thái:

```bash
~/appviewcamera/scripts/status.sh
```

Mỗi lần `start.sh` chạy thành công, Termux tự hiển thị API secret, API/RTSP port và chuỗi ghép nối Viewer. Có thể hiện lại bất cứ lúc nào bằng:

```bash
~/appviewcamera/scripts/pairing.sh
```

Lệnh này hiển thị chuỗi cho Viewer cùng điện thoại và QR cho Viewer ở máy khác. Trong
Viewer mở `Cài đặt`, chọn `Dán chuỗi ghép nối` hoặc `Quét QR`. Có thể chỉ định IP
LAN/Tailscale khi tạo QR bằng:

```bash
PAIR_HOST=100.x.y.z ~/appviewcamera/scripts/pairing.sh
```

## Cập nhật từ GitHub

Nếu source được clone tại `~/appviewcamera-source`, từ các phiên bản sau chỉ cần chạy:

```bash
~/appviewcamera-source/termux-gateway/scripts/update.sh
```

Script tự kiểm tra đúng repo `banupham/Appviewcamera`, đúng nhánh `main`, chạy `git pull --ff-only`, tự nạp lại
script mới rồi stop/install/start Gateway. Nếu phát hiện installer cũ còn pip, Pydantic hoặc maturin, script dừng trước
khi thay đổi bản đang hoạt động. Các file trong `~/appviewcamera/config` được giữ nguyên.

Nếu các file đã theo dõi trong source từng được sửa thủ công, script cất chúng vào `git stash` có tên
`appviewcamera-auto-update-...` trước khi pull thay vì ghi đè hoặc làm mất thay đổi.

Checksum MediaMTX hỗ trợ cả định dạng GNU (`hash *filename`) và định dạng hai khoảng trắng (`hash  filename`).
`status.sh` luôn trả JSON, kể cả khi process dừng hoặc API chưa mở cổng.

Dừng dịch vụ:

```bash
~/appviewcamera/scripts/stop.sh
```

## Cấu hình

Các file người dùng được giữ khi chạy lại installer:

- `config/gateway.json`: API, SQLite, MediaMTX và phạm vi quét LAN.
- `config/cameras.json`: camera, relay path; không chứa password.
- `config/recording.json`: ghi hình và retention.
- `config/google-drives.json`: danh sách remote và chính sách; không chứa OAuth token.
- `config/secrets.env`: API token và password camera, quyền file `600`.
- `config/rclone.conf`: do rclone tạo ở giai đoạn Google Drive, không trả về Viewer.

Mặc định Gateway tự xác định subnet `/24`. Nếu thiết bị chọn sai card mạng, đặt rõ:

```json
"discovery": {
  "subnets": ["192.168.1.0/24"]
}
```

Không đặt subnet quá rộng. `max_hosts` mặc định chặn quét quá 256 địa chỉ.

## Thêm camera qua API

Đọc token chỉ trên Gateway:

```bash
TOKEN="$(sed -n 's/^API_TOKEN=//p' ~/appviewcamera/config/secrets.env)"
```

Quét LAN:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8080/api/discovery/scan
```

Thêm camera:

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"id":"camera01","name":"Camera 01","host":"192.168.1.20","port":554,"username":"admin","password":"CHANGE_ME","main_path":"Streaming/Channels/101"}' \
  http://127.0.0.1:8080/api/cameras/camera01
```

Viewer sẽ mở relay tại `rtsp://IP_TAILSCALE_GATEWAY:8554/camera01`.

## Ghi hình, Drive và xem lại

- Bật ghi hình trong tab `Xem lại`; MediaMTX tạo segment fMP4 60 giây, không transcoding.
- Gateway lập chỉ mục SQLite, upload lên Drive đang dùng và xác minh kích thước trước khi đánh dấu thành công.
- Khi mất Internet, clip còn nguyên trên máy và hàng đợi tự thử lại sau 1, 5, 15 rồi 60 phút.
- Bản local chỉ được dọn sau khi upload đã xác minh; clip `Bảo vệ` không bị dọn tự động.
- Drive đạt 90% sẽ chuyển sang Drive khác. Retention chỉ xóa clip thường cũ hơn 7 ngày và dọn dần về 80%.
- Viewer chọn camera/ngày và phát clip local; nếu chỉ còn trên Drive, Gateway tải vào cache rồi phát bằng HTTP Range.
- Camera Hikvision bật `motion_enabled` dùng ISAPI port 80 để đánh dấu và bảo vệ clip chuyển động.

## Thêm Google Drive hoàn toàn từ Viewer

Trong tab `Lưu trữ`, chọn `Thêm Google Drive`, nhập mã/tên rồi bấm `Đăng nhập Google`. Viewer mở trình duyệt ngoài; Gateway chạy `rclone authorize` nền, nhận callback và lưu token thẳng vào `rclone.conf`. Token không được trả về hoặc lưu trong Viewer. Không cần chạy lệnh tạo token trong Termux cho từng tài khoản.

## Giới hạn hiện tại

- Android có thể chặn multicast, vì vậy ONVIF có thể không thấy camera; quét TCP subnet vẫn chạy.
- Binary MediaMTX Linux ARM64 phải được thử trên điện thoại đích. `doctor.sh` xác nhận file và
  bước tương tác tiếp theo sẽ xác nhận process/RTSP thật.
- `secrets.env` nằm trong sandbox Termux và không phải Android Keystore. Mã hóa yêu cầu mật khẩu
  sẽ mâu thuẫn với tự khởi động không cần người dùng sau reboot.
- Motion tự động hiện dùng sự kiện Hikvision ISAPI; camera hãng khác vẫn ghi liên tục và có thể bảo vệ clip thủ công.
- OAuth cần trình duyệt trên điện thoại Viewer và Internet trong lúc đăng nhập Google.
- Cần kiểm thử thực tế chuỗi mất mạng → có mạng → upload → phát lại Drive trước khi coi là bản phát hành ổn định.
