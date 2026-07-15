# Multi-Gateway Viewer

Viewer 1.3 lưu danh sách `gateways[]` trong SharedPreferences. Mỗi API token được mã hóa
riêng bằng `AndroidKeystoreCredentialCipher`; JSON lưu trữ chỉ chứa
`encrypted_api_token`.

Mỗi entry gồm `id`, `name`, `host`, `api_port`, `rtsp_port`, token mã hóa,
`last_seen`, `status`, `camera_count` và `is_default`. `current_gateway_id` xác định
Gateway mà toàn bộ tab Live, Playback, Devices và Storage đang sử dụng.

## Migration

Nếu Viewer chỉ có các key cũ `host`, `api_port`, `rtsp_port` và
`api_token_encrypted`, lần khởi động đầu sẽ chuyển chúng thành Gateway mặc định.
Không cần ghép nối lại.

## QR

Cơ chế QR cũ được giữ nguyên và chuỗi mới chỉ thêm `gateway_id`. Quét ID
mới sẽ mở form thêm Gateway. Quét ID đã có sẽ hiện hộp thoại xác nhận
trước khi cập nhật IP, cổng và token.

Khi chuyển Gateway, Viewer hủy request cũ, xóa state camera/clip/Drive và tạo lại
Compose subtree theo `current_gateway_id`, nhờ đó player cũ được release.

Xóa Gateway trong Settings chỉ xóa entry cục bộ. Viewer không gửi lệnh DELETE
camera, Drive, video hoặc YouTube tới Gateway.
