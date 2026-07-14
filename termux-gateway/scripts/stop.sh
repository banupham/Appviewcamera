#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
PID_FILE="$APP_HOME/run/gateway.pid"
if [ ! -f "$PID_FILE" ]; then
  echo "Gateway chưa chạy"
  exit 0
fi
PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  COUNT=0
  while kill -0 "$PID" 2>/dev/null && [ "$COUNT" -lt 10 ]; do
    sleep 1
    COUNT=$((COUNT + 1))
  done
fi
rm -f "$PID_FILE"
echo "Gateway đã dừng"
