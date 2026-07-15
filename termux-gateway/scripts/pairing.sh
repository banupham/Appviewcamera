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
LOCAL_URI="appviewcamera://pair?host=127.0.0.1&api_port=8080&rtsp_port=8554&token=$TOKEN"
echo "Chuỗi ghép nối cùng máy: $LOCAL_URI"
echo "========================================="
echo

echo "QR cùng máy (hoặc sao chép chuỗi phía trên):"
if command -v qrencode >/dev/null 2>&1; then
  qrencode -t ANSIUTF8 "$LOCAL_URI"
else
  echo "Chưa có qrencode; chạy update.sh để cài bổ sung."
fi

detect_lan_ip() {
  if [ -n "${PAIR_HOST:-}" ]; then
    printf '%s\n' "$PAIR_HOST"
    return
  fi
  if command -v ip >/dev/null 2>&1; then
    IP_OUTPUT="$(ip -o -4 addr show up scope global 2>/dev/null || true)"
    printf '%s\n' "$IP_OUTPUT" \
      | awk '$2 !~ /^(lo|tun|tailscale)/ {split($4, address, "/"); print address[1]; exit}'
  fi
}

LAN_IP="$(detect_lan_ip)"
if ! printf '%s' "$LAN_IP" | grep -Eq '^[A-Za-z0-9.-]+$'; then
  echo "PAIR_HOST/IP LAN không hợp lệ; bỏ qua QR cho máy khác." >&2
  LAN_IP=""
fi
if [ -n "$LAN_IP" ] && [ "$LAN_IP" != "127.0.0.1" ]; then
  LAN_URI="appviewcamera://pair?host=$LAN_IP&api_port=8080&rtsp_port=8554&token=$TOKEN"
  echo
  echo "Viewer ở máy khác - IP $LAN_IP:"
  echo "$LAN_URI"
  if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 "$LAN_URI"
  fi
else
  echo
  echo "Không tự phát hiện được IP LAN. Có thể chạy:"
  echo "PAIR_HOST=192.168.1.x $APP_HOME/scripts/pairing.sh"
  echo "hoặc thay 192.168.1.x bằng IP Tailscale của Gateway."
fi
