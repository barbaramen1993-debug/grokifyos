#!/usr/bin/env bash
# Build Grokify APK (debug by default; use: build.sh release)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT"
VARIANT="${1:-debug}"
case "$VARIANT" in
  debug)
    ./gradlew :app:assembleDebug
    APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    ./gradlew :app:assembleRelease
    APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
    ;;
  *)
    echo "Usage: $0 [debug|release]" >&2
    exit 1
    ;;
esac

echo ""
echo "APK: $APK"
ls -lh "$APK"
