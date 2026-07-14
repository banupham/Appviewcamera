#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SOURCE_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"

# Nếu source là git checkout, kéo main theo fast-forward rồi chạy lại chính
# script vừa cập nhật. Nhờ vậy tiến trình không tiếp tục bằng logic bản cũ.
if [ "${APPVIEWCAMERA_UPDATE_AFTER_PULL:-0}" != "1" ] && \
  REPO_ROOT="$(git -C "$SOURCE_DIR" rev-parse --show-toplevel 2>/dev/null)"; then
  ORIGIN_URL="$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)"
  case "$ORIGIN_URL" in
    https://github.com/banupham/Appviewcamera|https://github.com/banupham/Appviewcamera.git|git@github.com:banupham/Appviewcamera.git)
      ;;
    *)
      echo "Lỗi: source không trỏ tới repo banupham/Appviewcamera: $ORIGIN_URL" >&2
      echo "Hãy clone lại https://github.com/banupham/Appviewcamera.git" >&2
      exit 1
      ;;
  esac
  BRANCH="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short HEAD || true)"
  if [ "$BRANCH" != "main" ]; then
    echo "Lỗi: source đang ở nhánh '$BRANCH', cần nhánh main." >&2
    exit 1
  fi
  echo "[update] Kéo mã mới nhất từ origin/main"
  git -C "$REPO_ROOT" pull --ff-only origin main
  APPVIEWCAMERA_UPDATE_AFTER_PULL=1 exec "$SOURCE_DIR/scripts/update.sh"
fi

if grep -Eiq 'pip[[:space:]]+install|pydantic|fastapi|maturin' "$SOURCE_DIR/scripts/install.sh"; then
  echo "Lỗi: installer cũ có pip/Pydantic. Dừng để tránh lỗi maturin trên Termux." >&2
  echo "Hãy clone lại repo banupham/Appviewcamera rồi chạy update.sh." >&2
  exit 1
fi

SOURCE_VERSION="$(sed -n 's/^__version__ = "\([^"]*\)"/\1/p' "$SOURCE_DIR/src/appviewcamera_gateway/__init__.py")"
SOURCE_COMMIT="$(git -C "$SOURCE_DIR" rev-parse --short HEAD 2>/dev/null || printf 'artifact')"
echo "[update] Cài Gateway $SOURCE_VERSION từ source $SOURCE_COMMIT"

"$APP_HOME/scripts/stop.sh" || true
APPVIEWCAMERA_HOME="$APP_HOME" "$SOURCE_DIR/scripts/install.sh"
"$APP_HOME/scripts/start.sh"
"$APP_HOME/scripts/status.sh"
