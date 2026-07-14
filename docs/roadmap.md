# Kế hoạch triển khai

1. ✅ Khởi tạo project và APK tối thiểu.
2. ✅ Quản lý camera và kiểm tra RTSP.
3. ✅ Termux Gateway, tự tìm camera và RTSP relay (đã kiểm thử ARM64/H.264 1080p30).
4. 🔄 Viewer quản trị camera và xem live qua LAN.
5. Viewer xem qua Tailscale.
6. 🔄 Ghi video và kiểm tra clip (fMP4/SQLite/Viewer đã có; phục hồi mất mạng đang chờ test thiết bị).
7. Phát hiện chuyển động.
8. 🔄 SQLite và chỉ mục clip cục bộ đã có; còn upload state/retention nâng cao.
9. 🔄 Một tài khoản Google Drive qua rclone (API, quota và Viewer đã có; đang hoàn thiện OAuth trình duyệt).
10. Nhiều tài khoản Drive và upload queue.
11. Ước tính dung lượng và số ngày lưu.
12. Retention Manager và bảo vệ video.
13. Hoàn chỉnh REST API và pairing bảo mật.
14. 🔄 Playback danh sách clip đã có; calendar và timeline đang chờ.
15. 🔄 Quản lý Drive từ Viewer (thêm/xóa/kiểm tra đã có).
16. Ổn định, phục hồi lỗi và tối ưu.

Gateway APK thử nghiệm được giữ đến khi Giai đoạn 3 chạy trên điện thoại thật. Sau mỗi giai đoạn,
GitHub Actions phải xanh và artifact tương ứng phải tải được.
