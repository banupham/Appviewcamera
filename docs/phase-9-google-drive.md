# Giai đoạn lưu trữ Google Drive

## Kiến trúc bảo mật

- Viewer gọi REST API của Termux Gateway bằng Bearer token hiện có.
- OAuth token chỉ được gửi một lần khi thêm remote và không được Viewer ghi xuống bộ nhớ.
- Gateway lưu token trong `~/appviewcamera/config/rclone.conf` với quyền file `600`.
- `GET /api/storage/drives` chỉ trả metadata, trạng thái và quota; không có credential.
- Không mở rclone Remote Control ra LAN/Tailscale vì quyền RC tương đương quyền shell trên Gateway.

## API

- `GET /api/storage/drives`: danh sách tài khoản đã cấu hình.
- `POST /api/storage/drives`: thêm remote Drive từ token JSON của `rclone authorize drive`.
- `POST /api/storage/drives/{id}/refresh`: chạy `rclone about` để kiểm tra và đọc quota.
- `DELETE /api/storage/drives/{id}`: xóa metadata và section tương ứng trong `rclone.conf`.

Remote mới dùng scope `drive.file`: rclone chỉ thấy và quản lý những file do ứng dụng tạo. Điều này đủ cho thư mục
backup camera và giới hạn quyền so với toàn bộ Drive.

## Giới hạn của bản 0.5

Luồng OAuth trình duyệt hoàn toàn tự động chưa bật. Redirect mặc định của rclone quay về
`http://127.0.0.1:53682`, nên khi trình duyệt nằm trên điện thoại Viewer còn rclone nằm trên điện thoại Gateway
cần một cầu nối callback có kiểm tra session/state. Cho đến khi cầu nối này hoàn tất, màn hình Storage nhận token JSON
được tạo bởi `rclone authorize drive`; token không được lưu trên Viewer sau khi đóng hộp thoại.

Tài liệu chính thức:

- <https://rclone.org/remote_setup/>
- <https://rclone.org/commands/rclone_config_create/>
- <https://rclone.org/drive/>
