# Database schema

Database Gateway: `camera_gateway.db` (Room, schema version 1).

## `cameras`

| Cột | Kiểu | Nội dung |
|---|---|---|
| `id` | INTEGER PK | ID tự tăng |
| `name` | TEXT | Tên camera |
| `ip` | TEXT | IP hoặc hostname trong LAN |
| `port` | INTEGER | Cổng RTSP |
| `username` | TEXT | Username; không ghi vào log |
| `encryptedPassword` | TEXT | AES-GCM ciphertext từ Android Keystore |
| `mainRtspUrl` | TEXT | URL không chứa userinfo |
| `subRtspUrl` | TEXT | URL phụ không chứa userinfo |
| `relayPath` | TEXT UNIQUE | Path dành cho relay |
| `enabled` | INTEGER/BOOL | Camera được bật |
| `recordEnabled` | INTEGER/BOOL | Cho phép ghi |
| `motionEnabled` | INTEGER/BOOL | Cho phép motion detection |
| `audioEnabled` | INTEGER/BOOL | Cho phép audio |
| `connectionStatus` | TEXT | OFFLINE/CONNECTING/RECONNECTING/CONNECTED |
| `codec` | TEXT NULL | Codec/MIME từ Media3 |
| `width`, `height` | INTEGER NULL | Độ phân giải |
| `fps` | REAL NULL | Frame rate |
| `bitrate` | INTEGER NULL | Bitrate bps từ stream |
| `lastError` | TEXT NULL | Lỗi đã che credential |
| `lastTestedAt` | INTEGER NULL | Unix epoch milliseconds |
| `createdAt` | INTEGER | Unix epoch milliseconds |

Index: unique `relayPath`, `enabled`, `createdAt`.
