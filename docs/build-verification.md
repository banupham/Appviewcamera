# Xác nhận build

## Local — 2026-07-14

Môi trường:

- Eclipse Temurin JDK 17.0.19+10.
- Android SDK Platform 35.
- Android Build Tools 35.0.0 (AGP cũng cài 34.0.0 theo dependency).
- Gradle Wrapper 8.9.

Lệnh:

```powershell
.\gradlew.bat test :gateway:assembleDebug :viewer:assembleDebug --stacktrace --console=plain
```

Kết quả:

```text
BUILD SUCCESSFUL in 9m 8s
136 actionable tasks: 136 executed
```

Artifacts local:

```text
gateway/build/outputs/apk/debug/gateway-debug.apk
viewer/build/outputs/apk/debug/viewer-debug.apk
```

Xác nhận trên thiết bị thật và camera RTSP thật vẫn là bước riêng, không được suy ra chỉ từ build thành công.
