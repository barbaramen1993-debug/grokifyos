#!/usr/bin/env bash
# Install debug APK to a connected device (USB or wireless ADB).
# Examples:
#   ./scripts/install-device.sh
#   ./scripts/install-device.sh 192.168.1.20:5555
#   ADB_SERIAL=XXXX ./scripts/install-device.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

TARGET="${1:-}"
if [[ -n "$TARGET" ]]; then
  echo "Connecting wireless ADB: $TARGET"
  adb connect "$TARGET"
fi

if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB=(adb -s "$ADB_SERIAL")
else
  ADB=(adb)
fi

echo "Devices:"
"${ADB[@]}" devices -l

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "No debug APK — building..."
  "$ROOT/scripts/build.sh" debug
fi

echo "Installing $APK ..."
"${ADB[@]}" install -r "$APK"
echo "Done. Launch: adb shell am start -n io.grokify.os.debug/io.grokify.os.MainActivity"
