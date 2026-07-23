#!/usr/bin/env bash
# Copy free map GLBs + catalog into the GrokifyOS Android companion assets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/godot/companion-world"
DST="$ROOT/android/app/src/main/assets/companion/world"
mkdir -p "$DST/kenney"
if [[ -d "$SRC/maps/vendor/kenney-platformer/models" ]]; then
  cp -a "$SRC/maps/vendor/kenney-platformer/models/"*.glb "$DST/kenney/" 2>/dev/null || true
fi
if [[ -f "$SRC/maps/catalog.json" ]]; then
  cp "$SRC/maps/catalog.json" "$DST/catalog.json"
fi
echo "Synced Companion World assets → $DST"
ls -la "$DST/kenney" 2>/dev/null | head -20 || true
