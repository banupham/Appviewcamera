# Giai đoạn 3 — Termux Gateway và RTSP relay

## Lý do đổi kiến trúc

MediaMTX không phát hành target Android chính thức. Thử biên dịch `GOOS=android` gặp lỗi liên kết,
trong khi binary Linux ARM64 không CGO đã build được nhưng cần kiểm tra trên thiết bị. Vì vậy Gateway
runtime chuyển sang Termux để dùng MediaMTX, Python, SQLite và rclone như các process thông thường.
Viewer vẫn là Android native.

## Phạm vi đã cài đặt trong source

- Cấu hình tách riêng cho Gateway, camera, recording và Google Drive.
- Password camera tách sang `secrets.env`, API không trả password.
- REST API Bearer token dùng HTTP server trong thư viện chuẩn Python, không phụ thuộc FastAPI/Pydantic.
- ONVIF WS-Discovery, fallback quét TCP subnet có giới hạn.
- SQLite index kết quả discovery.
- Sinh `mediamtx.yml` từ camera đang bật.
- MediaMTX supervisor và exponential backoff.
- Installer có kiểm tra checksum release MediaMTX.
- Termux:Boot, start, stop, status và doctor.
- GitHub Actions test và đóng gói ZIP.

## Chưa tuyên bố hoạt động

- MediaMTX trên điện thoại cụ thể trước khi chạy `doctor.sh` và thử một camera thật.
- Ghi MP4/fMP4, kiểm tra FFprobe, motion, upload Drive và retention.
- Viewer live qua API cấu hình mới.

Các phần này tiếp tục theo thứ tự roadmap sau khi Giai đoạn 3 được xác nhận trên thiết bị.

## Cập nhật an toàn

Các lần sau chỉ cần một lệnh:

```bash
~/appviewcamera-source/termux-gateway/scripts/update.sh
```

Script tự pull `origin/main` theo fast-forward và từ chối installer cũ có pip/Pydantic để không tái diễn lỗi
`pydantic-core`/`maturin` trên Android.
