#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
PID_FILE="$APP_HOME/run/gateway.pid"
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  TOKEN="$(sed -n 's/^API_TOKEN=//p' "$APP_HOME/config/secrets.env")"
  curl --fail --silent --show-error -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8080/api/status"
  printf '\n'
else
  echo "Gateway đang dừng"
  exit 1
fi
