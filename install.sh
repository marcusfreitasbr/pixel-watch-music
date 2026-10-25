#!/usr/bin/env bash
# Installs music-player.apk (in this folder) on a Wear OS watch over Wi-Fi.
#   bash install.sh
# Needs: the watch and this computer on the same Wi-Fi. adb is downloaded from Google if it isn't installed.
set -euo pipefail
cd "$(dirname "$0")"

fail() { printf '\n%s\n' "$1" >&2; exit 1; }
ask() { local answer; read -r -p "$1: " answer; echo "$answer"; }

[ -f music-player.apk ] || fail "music-player.apk isn't in this folder."

# --- adb: one on PATH, or Google's platform-tools fetched next to this script ---
if command -v adb >/dev/null; then
    adb=adb
else
    adb=./platform-tools/adb
    if [ ! -x "$adb" ]; then
        case "$(uname -s)" in
            Darwin) os=darwin ;;
            *) os=linux ;;
        esac
        echo "Downloading Google's platform-tools (adb)..."
        curl -fsSL -o /tmp/platform-tools.zip "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
        unzip -qo /tmp/platform-tools.zip -d .
        rm /tmp/platform-tools.zip
    fi
fi

# --- connect to the watch ---
cat <<'TEXT'

On the watch:
  1. Settings > System > About > Versions: tap "Build number" 7 times to turn on Developer options.
  2. Settings > Developer options: turn on "ADB debugging", then "Wireless debugging".
  3. In Wireless debugging, tap "Pair new device". Keep that screen open.
TEXT
pair_address=$(ask 'IP address & port shown under "Pair new device" (e.g. 192.168.1.20:37123)')
pair_code=$(ask 'Wi-Fi pairing code shown on the watch')
"$adb" pair "$pair_address" "$pair_code" || fail "Pairing failed. Check the address and code, and that both are on the same Wi-Fi."

ip=${pair_address%%:*}
echo
echo 'Now go back one screen. Wireless debugging shows "IP address & port" (a different port).'
port=$(ask "That port (the number after $ip:)")
device="$ip:$port"
"$adb" connect "$device"
[ "$("$adb" -s "$device" get-state 2>/dev/null)" = device ] || fail "Couldn't connect to $device."

# --- install ---
echo
echo "Installing Music..."
result=$("$adb" -s "$device" install -r music-player.apk 2>&1 || true)
echo "$result"
case "$result" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        fail "A copy signed with a different key is installed. Uninstall it on the watch first, then run this again." ;;
    *Success*) ;;
    *) fail "Installing failed." ;;
esac
# The app asks for these itself on first launch; granting them here just saves the taps.
for p in READ_MEDIA_AUDIO POST_NOTIFICATIONS; do
    "$adb" -s "$device" shell pm grant dev.watchmusic "android.permission.$p"
done

echo
echo "Done: open Music on the watch. You can turn Wireless debugging off again; it uses battery."
