#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
FAILED=0
check_command() {
  if command -v "$1" >/dev/null 2>&1; then
    echo "OK   $1"
  else
    echo "FAIL $1"
    FAILED=1
  fi
}

check_command python
check_command ffprobe
check_command rclone
check_command curl
[ -x "$APP_HOME/bin/mediamtx" ] && echo "OK   mediamtx" || { echo "FAIL mediamtx"; FAILED=1; }
[ -s "$APP_HOME/config/secrets.env" ] && echo "OK   secrets.env" || { echo "FAIL secrets.env"; FAILED=1; }
APPVIEWCAMERA_HOME="$APP_HOME" "$APP_HOME/.venv/bin/python" -c 'from appviewcamera_gateway.config import GatewaySettings; GatewaySettings.load(); print("OK   gateway.yaml")'
RCLONE_CONFIG="$APP_HOME/config/rclone.conf" rclone listremotes >/dev/null
echo "OK   rclone.conf"
exit "$FAILED"
