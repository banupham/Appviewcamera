#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SOURCE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
"$APP_HOME/scripts/stop.sh" || true
APPVIEWCAMERA_HOME="$APP_HOME" "$SOURCE_DIR/scripts/install.sh"
"$APP_HOME/scripts/start.sh"
