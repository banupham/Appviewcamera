#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
SECRETS_FILE="$APP_HOME/config/secrets.env"
TOKEN="$(sed -n 's/^API_TOKEN=//p' "$SECRETS_FILE" | head -n 1)"

if [ -z "$TOKEN" ]; then
  echo "Không tìm thấy API_TOKEN trong $SECRETS_FILE" >&2
  exit 1
fi

echo
echo "========== KẾT NỐI APP VIEWER =========="
echo "Viewer cùng điện thoại: 127.0.0.1"
echo "Viewer điện thoại khác: dùng IP hiển thị trong ứng dụng Tailscale"
echo "API port: 8080"
echo "RTSP port: 8554"
echo "API secret: $TOKEN"
echo "Chuỗi ghép nối: appviewcamera://pair?host=127.0.0.1&api_port=8080&rtsp_port=8554&token=$TOKEN"
echo "========================================="
echo
