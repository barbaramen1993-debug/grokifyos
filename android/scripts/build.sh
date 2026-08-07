#!/usr/bin/env bash
# Build Grokify APK(s).
# Usage:
#   ./scripts/build.sh [debug|release] [phone|wear|wear-face|all]
# Defaults: debug phone
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT"
VARIANT="${1:-debug}"
TARGET="${2:-phone}"

case "$VARIANT" in
  debug|release) ;;
  *)
    echo "Usage: $0 [debug|release] [phone|wear|wear-face|all]" >&2
    exit 1
    ;;
esac

case "$TARGET" in
  phone|wear|wear-face|all) ;;
  *)
    echo "Usage: $0 [debug|release] [phone|wear|wear-face|all]" >&2
    exit 1
    ;;
esac

build_phone() {
  if [[ "$VARIANT" == "debug" ]]; then
    ./gradlew :app:assembleDebug
    APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  else
    ./gradlew :app:assembleRelease
    APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
  fi
  echo ""
  echo "Phone APK: $APK"
  ls -lh "$APK"
}

build_wear() {
  if [[ "$VARIANT" == "debug" ]]; then
    ./gradlew :wear:assembleDebug
    APK="$ROOT/wear/build/outputs/apk/debug/wear-debug.apk"
  else
    ./gradlew :wear:assembleRelease
    APK="$ROOT/wear/build/outputs/apk/release/wear-release-unsigned.apk"
  fi
  echo ""
  echo "Wear APK: $APK"
  ls -lh "$APK"
}

build_wear_face() {
  if [[ "$VARIANT" == "debug" ]]; then
    ./gradlew :wear-face:assembleDebug
    APK="$ROOT/wear-face/build/outputs/apk/debug/wear-face-debug.apk"
  else
    ./gradlew :wear-face:assembleRelease
    APK="$ROOT/wear-face/build/outputs/apk/release/wear-face-release-unsigned.apk"
  fi
  echo ""
  echo "Wear-face APK: $APK"
  ls -lh "$APK"
}

case "$TARGET" in
  phone) build_phone ;;
  wear) build_wear ;;
  wear-face) build_wear_face ;;
  all)
    build_phone
    build_wear
    build_wear_face
    ;;
esac
