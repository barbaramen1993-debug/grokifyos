# Companion World in-app (no side package)

**Date:** 2026-07-21  
**Status:** Implemented  
**Module:** `companion`

## Goal

Ship Godot companion-world maps **inside** GrokifyOS so remote OTA users never install a second APK.

## Approach

| Layer | Role |
|-------|------|
| `godot/companion-world` | Source of truth (desktop Godot + map scripts) |
| `assets/companion/world/` | Bundled GLBs + catalog (synced via `scripts/sync_to_android.sh`) |
| `companion-world-maps.js` | Build maps in Three.js (same ids / layouts as WorldBridge) |
| `CompanionStage.loadMap / enterWorld / nextMap` | Runtime API |
| Settings → **Open Companion World** | Calls `enterWorld("proto_arena")` on the live stage |

## Maps

- `proto_arena` — flat pad + walls  
- `kenney_plaza` — Kenney CC0 platforms (GLB)  
- `courtyard` — primitive plaza  
- `mini_dungeon` — multi-room dungeon  

On-stage **MAP** button cycles maps (or enters world from default stage floor).

## Sync

```bash
godot/companion-world/scripts/sync_to_android.sh
```

## Not in this pass

- Full Godot engine AAR embed (optional later; maps run in Web stage today)
- Runtime VRM swap for a second player body
