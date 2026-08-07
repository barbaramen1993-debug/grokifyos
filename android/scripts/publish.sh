#!/usr/bin/env bash
# Build Grokify APK and publish it to the website download store.
# Usage:
#   publish.sh [debug|release] [--channel phone|wear|wear-face] [--no-build] [--changelog "notes"]
# Defaults: debug, channel=phone
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

VARIANT="debug"
CHANNEL="phone"
NO_BUILD=0
CHANGELOG="Server build $(date -u +%Y-%m-%dT%H:%MZ)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    debug|release) VARIANT="$1"; shift ;;
    phone|wear|wear-face) CHANNEL="$1"; shift ;;
    --channel)
      CHANNEL="${2:-}"
      shift 2
      ;;
    --channel=*)
      CHANNEL="${1#--channel=}"
      shift
      ;;
    --no-build) NO_BUILD=1; shift ;;
    --changelog)
      CHANGELOG="${2:-}"
      shift 2
      ;;
    --changelog=*)
      CHANGELOG="${1#--changelog=}"
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [debug|release] [--channel phone|wear|wear-face] [--no-build] [--changelog \"notes\"]"
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

case "$CHANNEL" in
  phone|wear|wear-face) ;;
  *) echo "Invalid channel: $CHANNEL (use phone|wear|wear-face)" >&2; exit 1 ;;
esac

if [[ "$NO_BUILD" -eq 0 ]]; then
  "$ROOT/scripts/build.sh" "$VARIANT" "$CHANNEL"
fi

if [[ "$VARIANT" == "release" ]]; then
  FROM_FLAG="--from-release"
else
  FROM_FLAG="--from-debug"
fi

MIN_SDK=26
if [[ "$CHANNEL" == "wear" ]]; then
  MIN_SDK=30
elif [[ "$CHANNEL" == "wear-face" ]]; then
  MIN_SDK=33
fi

php "$ROOT/scripts/publish-apk.php" "$FROM_FLAG" --channel="$CHANNEL" --auto --changelog="$CHANGELOG" --min-sdk="$MIN_SDK"
