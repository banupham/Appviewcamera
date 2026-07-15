#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
PID_FILE="$APP_HOME/run/gateway.pid"
mkdir -p "$APP_HOME/run" "$APP_HOME/logs"

if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" 2>/dev/null; then
    echo "Gateway đang chạy với PID $PID"
    if [ -x "$APP_HOME/scripts/pairing.sh" ]; then
      "$APP_HOME/scripts/pairing.sh"
    fi
    exit 0
  fi
  rm -f "$PID_FILE"
fi

export APPVIEWCAMERA_HOME="$APP_HOME"
export RCLONE_CONFIG="$APP_HOME/config/rclone.conf"
export PYTHONPATH="$APP_HOME/src"
if [ -f "$APP_HOME/logs/launcher.log" ] && [ "$(wc -c < "$APP_HOME/logs/launcher.log")" -gt 5242880 ]; then
  mv -f "$APP_HOME/logs/launcher.log" "$APP_HOME/logs/launcher.log.1"
fi
nohup python -m appviewcamera_gateway >> "$APP_HOME/logs/launcher.log" 2>&1 &
PID=$!
printf '%s\n' "$PID" > "$PID_FILE"
sleep 1
if ! kill -0 "$PID" 2>/dev/null; then
  rm -f "$PID_FILE"
  echo "Gateway không khởi động được. Xem $APP_HOME/logs/launcher.log" >&2
  exit 1
fi
echo "Gateway đã chạy với PID $PID"
"$APP_HOME/scripts/pairing.sh"
