#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SOURCE_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
TARGET_BRANCH="test/playback-ui"

# Nếu source là git checkout, kéo nhánh test/playback-ui theo fast-forward
# rồi chạy lại chính script vừa cập nhật.
if [ "${APPVIEWCAMERA_UPDATE_AFTER_PULL:-0}" != "1" ] &&   REPO_ROOT="$(git -C "$SOURCE_DIR" rev-parse --show-toplevel 2>/dev/null)"; then

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

  if [ "$BRANCH" != "$TARGET_BRANCH" ]; then
    echo "Lỗi: source đang ở nhánh '$BRANCH', cần nhánh '$TARGET_BRANCH'." >&2
    echo "Chuyển nhánh bằng: git -C $REPO_ROOT switch $TARGET_BRANCH" >&2
    exit 1
  fi

  if [ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ]; then
    STASH_NAME="appviewcamera-auto-update-$(date -u +%Y%m%dT%H%M%SZ)"
    echo "[update-gateway] Cất thay đổi source cục bộ vào git stash: $STASH_NAME"
    git -C "$REPO_ROOT" stash push --message "$STASH_NAME"
    echo "[update-gateway] Có thể xem lại bằng: git -C $REPO_ROOT stash list"
  fi

  echo "[update-gateway] Kéo mã mới nhất từ origin/$TARGET_BRANCH"
  git -C "$REPO_ROOT" pull --ff-only origin "$TARGET_BRANCH"

  UPDATED_SCRIPT="$SOURCE_DIR/scripts/updategateway.sh"
  chmod +x "$UPDATED_SCRIPT"
  APPVIEWCAMERA_UPDATE_AFTER_PULL=1 exec "$UPDATED_SCRIPT"
fi

if grep -Eiq 'pip[[:space:]]+install|pydantic|fastapi|maturin'   "$SOURCE_DIR/scripts/install.sh"; then

  echo "Lỗi: installer cũ có pip/Pydantic. Dừng để tránh lỗi maturin trên Termux." >&2
  echo "Hãy clone lại repo banupham/Appviewcamera rồi chạy updategateway.sh." >&2
  exit 1
fi

SOURCE_VERSION="$(
  sed -n 's/^__version__ = "\([^"]*\)"/\1/p'     "$SOURCE_DIR/src/appviewcamera_gateway/__init__.py"
)"

SOURCE_COMMIT="$(
  git -C "$SOURCE_DIR" rev-parse --short HEAD 2>/dev/null     || printf 'artifact'
)"

echo "[update-gateway] Cài Gateway $SOURCE_VERSION từ source $SOURCE_COMMIT"

"$APP_HOME/scripts/stop.sh" || true

APPVIEWCAMERA_HOME="$APP_HOME"   "$SOURCE_DIR/scripts/install.sh"

"$APP_HOME/scripts/start.sh"
"$APP_HOME/scripts/status.sh"
