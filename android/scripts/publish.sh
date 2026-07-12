#!/usr/bin/env bash
# Build Grokify APK and publish it to the website download store.
# Usage: publish.sh [debug|release] [--no-build] [--changelog "notes"]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

VARIANT="debug"
NO_BUILD=0
CHANGELOG="Server build $(date -u +%Y-%m-%dT%H:%MZ)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    debug|release) VARIANT="$1"; shift ;;
    --no-build) NO_BUILD=1; shift ;;
    --changelog) CHANGELOG="${2:-}"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [debug|release] [--no-build] [--changelog \"notes\"]"
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ "$NO_BUILD" -eq 0 ]]; then
  "$ROOT/scripts/build.sh" "$VARIANT"
fi

if [[ "$VARIANT" == "release" ]]; then
  FROM_FLAG="--from-release"
else
  FROM_FLAG="--from-debug"
fi

php "$ROOT/scripts/publish-apk.php" "$FROM_FLAG" --auto --changelog="$CHANGELOG" --min-sdk=26
