#!/usr/bin/env bash
# Connect to a phone over ADB-on-TCP and (re)install the GrabReel APK.
#
#   ./push.sh <host:port> [path-to-apk]
#
# <host:port> is your phone's wireless-debugging ADB endpoint
# (Settings → Developer options → Wireless debugging → "IP address & Port").
set -euo pipefail

DEVICE="${1:?usage: ./push.sh <host:port> [apk]   (phone's wireless-debugging adb endpoint)}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"

ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB="adb"
command -v "$ADB" >/dev/null 2>&1 || { echo "adb not found (install platform-tools or set ANDROID_HOME)"; exit 1; }

cd "$(dirname "$0")"
[ -f "$APK" ] || { echo "APK not found at $APK — run ./gradlew assembleDebug first"; exit 1; }

echo "→ adb connect $DEVICE"
if ! "$ADB" connect "$DEVICE"; then
  cat <<EOF

Could not reach $DEVICE. Checklist:
  • Phone: Settings → Developer options → Wireless debugging = ON
    (first connection from a new machine also needs: adb pair <host>:<pairPort> <code>)
  • This machine must be able to reach the phone over the network (same LAN, or a
    VPN/overlay such as Tailscale). The wireless-debug port rotates, so re-check
    it if you get "Connection refused".
EOF
  exit 1
fi

echo "→ adb -s $DEVICE install -r -d \"$APK\""
"$ADB" -s "$DEVICE" install -r -d "$APK"
echo "✓ Installed. Launch 'GrabReel' from the app drawer."
