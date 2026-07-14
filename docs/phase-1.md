# Giai đoạn 1 — Khởi tạo project

## Đã thêm

- Gradle project mới hoàn toàn với hai module application `gateway` và `viewer`.
- Kotlin, Jetpack Compose, minSdk 26, targetSdk/compileSdk 35 và Java 17.
- Gateway hiển thị trạng thái thực tế `Chưa cấu hình`, số camera và số camera đang ghi.
- Viewer có năm khu vực bắt buộc: Trực tiếp, Xem lại, Thiết bị, Lưu trữ, Cài đặt.
- Unit test kiểm tra bất biến trạng thái Gateway và cấu trúc điều hướng Viewer.
- Gradle Wrapper 8.9 và GitHub Actions build hai APK.

## Kiểm tra môi trường local ngày 2026-07-14

| Thành phần | Kết quả |
|---|---|
| JDK/`java` | Không khả dụng trong `PATH` |
| `ANDROID_HOME` | Chưa cấu hình |
| `ANDROID_SDK_ROOT` | Chưa cấu hình |
| `adb` | Không khả dụng trong `PATH` |

Do đó local build/test chưa thể chạy và chưa có APK local. Workflow GitHub Actions là đường build được cấu hình cho repository.

## Chưa triển khai

- Camera CRUD và kiểm tra RTSP.
- Foreground Service, Boot Receiver và watchdog.
- MediaMTX/RTSP relay và phát live.
- Ghi hình, phát hiện chuyển động và cơ sở dữ liệu clip.
- REST API, pairing, Google Drive/rclone, upload queue và retention.

Không có IP, RTSP URL, mật khẩu, API token hoặc OAuth token hard-code.
