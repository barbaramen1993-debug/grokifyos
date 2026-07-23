#!/usr/bin/env bash
# Fetch free OSS map packs into maps/vendor/ for Companion World.
# Usage:
#   ./scripts/fetch_map_pack.sh list
#   ./scripts/fetch_map_pack.sh kenney-platformer
#   ./scripts/fetch_map_pack.sh kaykit-dungeon
#   ./scripts/fetch_map_pack.sh kenney-city
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/maps/vendor"
mkdir -p "$VENDOR"

list() {
  cat <<EOF
Available packs (free / open source):
  kenney-platformer  https://github.com/KenneyNL/Starter-Kit-3D-Platformer  (CC0 models)
  kenney-city        https://github.com/KenneyNL/Starter-Kit-City-Builder   (CC0 models)
  kenney-fps         https://github.com/KenneyNL/Starter-Kit-FPS            (CC0 models)
  kaykit-dungeon     https://github.com/KayKit-Game-Assets/KayKit-Dungeon-Remastered-1.0  (CC0)

After fetch, open Godot once so .glb/.gltf reimport, then instance under maps/<id>.tscn
and register the id in WorldBridge.list_maps() / maps/catalog.json.
EOF
}

sparse_models() {
  local name="$1"
  local url="$2"
  local paths="$3"
  local dest="$VENDOR/$name"
  echo "→ $name from $url"
  rm -rf "$dest"
  mkdir -p "$dest"
  local tmp
  tmp="$(mktemp -d)"
  git clone --depth 1 --filter=blob:none --sparse "$url" "$tmp/repo"
  (
    cd "$tmp/repo"
    # shellcheck disable=SC2086
    git sparse-checkout set $paths
  )
  # Copy models / gltf / glb / objects
  for p in $paths; do
    if [[ -d "$tmp/repo/$p" ]]; then
      mkdir -p "$dest/$(basename "$p")"
      cp -a "$tmp/repo/$p/." "$dest/$(basename "$p")/" 2>/dev/null || true
    fi
  done
  if [[ -f "$tmp/repo/LICENSE.md" ]]; then
    cp "$tmp/repo/LICENSE.md" "$dest/LICENSE.md"
  elif [[ -f "$tmp/repo/LICENSE" ]]; then
    cp "$tmp/repo/LICENSE" "$dest/LICENSE"
  elif [[ -f "$tmp/repo/LICENSE.txt" ]]; then
    cp "$tmp/repo/LICENSE.txt" "$dest/LICENSE.txt"
  fi
  echo "  source=$url" >"$dest/SOURCE.txt"
  rm -rf "$tmp"
  echo "✓ vendored → $dest ($(du -sh "$dest" | awk '{print $1}'))"
}

clone_full_assets() {
  local name="$1"
  local url="$2"
  local dest="$VENDOR/$name"
  echo "→ $name (full shallow) from $url"
  rm -rf "$dest"
  git clone --depth 1 "$url" "$dest"
  echo "✓ vendored → $dest ($(du -sh "$dest" | awk '{print $1}'))"
}

cmd="${1:-list}"
case "$cmd" in
  list|-h|--help) list ;;
  kenney-platformer)
    sparse_models "kenney-platformer" \
      "https://github.com/KenneyNL/Starter-Kit-3D-Platformer" \
      "models objects"
    ;;
  kenney-city)
    sparse_models "kenney-city" \
      "https://github.com/KenneyNL/Starter-Kit-City-Builder" \
      "models assets"
    ;;
  kenney-fps)
    sparse_models "kenney-fps" \
      "https://github.com/KenneyNL/Starter-Kit-FPS" \
      "models assets"
    ;;
  kaykit-dungeon)
    clone_full_assets "kaykit-dungeon" \
      "https://github.com/KayKit-Game-Assets/KayKit-Dungeon-Remastered-1.0"
    ;;
  *)
    echo "Unknown pack: $cmd" >&2
    list
    exit 1
    ;;
esac
