# Giai đoạn 2 — Quản lý camera và kiểm tra RTSP

## Chức năng đã triển khai

- Thêm, sửa và xóa camera trong Gateway.
- Lưu bằng Room/SQLite, query danh sách theo tên và index `relayPath`, `enabled`, `createdAt`.
- Trường cấu hình: tên, IP/hostname, port, username, main/sub RTSP URL, relay path, enabled, record, motion và audio.
- Mật khẩu mã hóa AES-GCM bằng khóa không export được trong Android Keystore.
- Không lưu user/password trong RTSP URL; credential chỉ được ghép trong bộ nhớ ngay trước khi kết nối.
- Kiểm tra RTSP bằng Media3 RTSP và bắt buộc RTP-over-TCP.
- Timeout 12 giây mỗi lần; retry sau 5, 10 và 30 giây; các lần tiếp theo trong hệ thống nền sẽ dùng 60 giây.
- Trạng thái `OFFLINE`, `CONNECTING`, `RECONNECTING`, `CONNECTED`.
- Lưu/hiển thị codec, width, height, FPS và bitrate do stream khai báo.
- Che credential khỏi exception/log và giới hạn độ dài lỗi lưu trong database.
- Coroutine cancellation dừng probe/retry và ExoPlayer luôn được release trong `finally`.

## Unit test đã thêm

- Kiểm tra RTSP URL và loại userinfo trước khi lưu.
- Percent-encode username/password chỉ khi kết nối.
- Kiểm tra validation camera, port, URL và relay path.
- Kiểm tra lịch backoff 5/10/30/60 giây.
- Kiểm tra credential redaction.

## Giới hạn hiện tại

- Unit test và debug build đã chạy thành công local ngày 2026-07-14 bằng JDK 17, Android SDK 35 và Gradle 8.9.
- Bitrate chỉ hiển thị khi RTSP/SDP/decoder cung cấp; đo byte trong 60 giây thuộc giai đoạn Storage Estimator.
- Chưa có snapshot hoặc xem live trong Gateway.
- Chưa chạy Foreground Service, MediaMTX hoặc RTSP relay; đây là Giai đoạn 3.
- Cấu hình hiện chỉ nằm trên Gateway; REST API và đồng bộ Viewer thuộc giai đoạn sau.

## Lệnh kiểm tra dự kiến

```powershell
.\gradlew.bat :gateway:testDebugUnitTest
.\gradlew.bat :gateway:assembleDebug
```

Lần xác nhận đầy đủ đã chạy:

```powershell
.\gradlew.bat test :gateway:assembleDebug :viewer:assembleDebug --stacktrace --console=plain
```

Kết quả: `BUILD SUCCESSFUL`, 136 tasks thực thi.
