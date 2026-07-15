#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SOURCE_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
APP_HOME="${APPVIEWCAMERA_HOME:-$HOME/appviewcamera}"
MEDIAMTX_VERSION="${MEDIAMTX_VERSION:-1.18.2}"

if ! command -v pkg >/dev/null 2>&1; then
  echo "Lỗi: script này phải chạy trong Termux." >&2
  exit 1
fi

echo "[1/5] Cài gói Termux"
pkg install -y python curl tar ffmpeg rclone libqrencode iproute2

echo "[2/5] Tạo thư mục $APP_HOME"
mkdir -p "$APP_HOME"/{bin,config,data,logs,recordings,run,scripts,src}
if [ -f "$APP_HOME/.venv/pyvenv.cfg" ]; then
  echo "Xóa virtual environment cũ không còn được sử dụng"
  rm -rf -- "$APP_HOME/.venv"
fi
if [ "$SOURCE_DIR" != "$APP_HOME" ]; then
  cp "$SOURCE_DIR/pyproject.toml" "$APP_HOME/pyproject.toml"
  cp -R "$SOURCE_DIR/src/." "$APP_HOME/src/"
  cp -R "$SOURCE_DIR/scripts/." "$APP_HOME/scripts/"
  cp "$SOURCE_DIR/README.md" "$APP_HOME/README.md"
  for config in gateway.json cameras.json recording.json google-drives.json; do
    if [ ! -f "$APP_HOME/config/$config" ]; then
      cp "$SOURCE_DIR/config/$config" "$APP_HOME/config/$config"
    fi
  done
fi

echo "[3/5] Tạo API token"
SECRETS_FILE="$APP_HOME/config/secrets.env"
if [ ! -s "$SECRETS_FILE" ]; then
  TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
  printf 'API_TOKEN=%s\n' "$TOKEN" > "$SECRETS_FILE"
fi
chmod 600 "$SECRETS_FILE"
touch "$APP_HOME/config/rclone.conf"
chmod 600 "$APP_HOME/config/rclone.conf"

echo "[4/5] Tải MediaMTX $MEDIAMTX_VERSION"
case "$(uname -m)" in
  aarch64|arm64) ARCHIVE_ARCH="arm64" ;;
  armv7l|armv8l) ARCHIVE_ARCH="armv7" ;;
  x86_64|amd64) ARCHIVE_ARCH="amd64" ;;
  *) echo "Kiến trúc $(uname -m) chưa được hỗ trợ." >&2; exit 1 ;;
esac
ARCHIVE="mediamtx_v${MEDIAMTX_VERSION}_linux_${ARCHIVE_ARCH}.tar.gz"
BASE_URL="https://github.com/bluenviron/mediamtx/releases/download/v${MEDIAMTX_VERSION}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
curl --fail --location --retry 3 --output "$TEMP_DIR/$ARCHIVE" "$BASE_URL/$ARCHIVE"
curl --fail --location --retry 3 --output "$TEMP_DIR/checksums.sha256" "$BASE_URL/checksums.sha256"
EXPECTED_HASH="$(awk -v archive="$ARCHIVE" '
  {
    name = $2
    sub(/^\*/, "", name)
    if (name == archive) {
      print $1
      exit
    }
  }
' "$TEMP_DIR/checksums.sha256")"
if ! printf '%s\n' "$EXPECTED_HASH" | grep -Eq '^[0-9a-fA-F]{64}$'; then
  echo "Không tìm thấy checksum hợp lệ của $ARCHIVE" >&2
  exit 1
fi
(cd "$TEMP_DIR" && printf '%s  %s\n' "$EXPECTED_HASH" "$ARCHIVE" | sha256sum --check -)
tar -xzf "$TEMP_DIR/$ARCHIVE" -C "$TEMP_DIR"
install -m 700 "$TEMP_DIR/mediamtx" "$APP_HOME/bin/mediamtx"

echo "[5/5] Cấu hình tự chạy sau reboot"
mkdir -p "$HOME/.termux/boot"
BOOT_SCRIPT="$HOME/.termux/boot/20-appviewcamera-gateway"
printf '%s\n' '#!/data/data/com.termux/files/usr/bin/bash' > "$BOOT_SCRIPT"
printf '%s\n' 'command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock' >> "$BOOT_SCRIPT"
printf 'APPVIEWCAMERA_HOME=%q %q\n' "$APP_HOME" "$APP_HOME/scripts/start.sh" >> "$BOOT_SCRIPT"
chmod 700 "$BOOT_SCRIPT" "$APP_HOME/scripts/"*.sh

echo
echo "Cài đặt hoàn tất. API token nằm trong: $SECRETS_FILE"
echo "Chạy: $APP_HOME/scripts/start.sh"
echo "Kiểm tra: $APP_HOME/scripts/doctor.sh"
