# Kế hoạch triển khai

1. ✅ Khởi tạo project và APK tối thiểu.
2. ✅ Quản lý camera và kiểm tra RTSP.
3. ✅ Termux Gateway, tự tìm camera và RTSP relay (đã kiểm thử ARM64/H.264 1080p30).
4. ✅ Viewer quản trị camera và xem live qua LAN.
5. ✅ Viewer dùng IP Tailscale như IP riêng thông thường; không mở NAT.
6. ✅ Ghi fMP4/SQLite, không xóa trước khi upload xác minh.
7. ✅ Motion Hikvision ISAPI và tự bảo vệ clip liên quan.
8. ✅ SQLite lifecycle, upload retry, motion và deletion history.
9. ✅ Google Drive qua rclone, quota và quản lý token từ Viewer.
10. ✅ Nhiều Drive, chọn thủ công và tự chuyển ở ngưỡng 90%.
11. ✅ Ước tính bitrate, dung lượng/ngày và thời gian lưu.
12. ✅ Retention 90% → 80%, tuổi tối thiểu 7 ngày và clip bảo vệ.
13. 🔄 REST API Bearer đã có; QR pairing còn là nâng cấp sau kiểm thử sản phẩm.
14. 🔄 Chọn ngày và phát local/Drive đã có; timeline 24 giờ trực quan còn nâng cấp.
15. ✅ Quản lý Drive từ Viewer.
16. 🔄 Watchdog, retry, boot và log rotation đã có; đang chờ kiểm thử mất mạng/reboot trên thiết bị.

Gateway APK thử nghiệm được giữ đến khi Giai đoạn 3 chạy trên điện thoại thật. Sau mỗi giai đoạn,
GitHub Actions phải xanh và artifact tương ứng phải tải được.
