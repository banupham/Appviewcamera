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
if [ -x "$APP_HOME/bin/mediamtx" ]; then
  echo "OK   mediamtx"
else
  echo "FAIL mediamtx"
  FAILED=1
fi
if [ -s "$APP_HOME/config/secrets.env" ]; then
  echo "OK   secrets.env"
else
  echo "FAIL secrets.env"
  FAILED=1
fi
APPVIEWCAMERA_HOME="$APP_HOME" PYTHONPATH="$APP_HOME/src" python -c 'from appviewcamera_gateway.config import GatewaySettings; GatewaySettings.load(); print("OK   gateway.json")'
RCLONE_CONFIG="$APP_HOME/config/rclone.conf" rclone listremotes >/dev/null
echo "OK   rclone.conf"
exit "$FAILED"
