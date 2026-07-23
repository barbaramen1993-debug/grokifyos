# Companion free maps + idle locomotion polish

**Date:** 2026-07-21  
**Status:** Implemented  

## Idle / walk issues fixed (Web VRM stage)

1. **Elbows bent backwards on idle** — hang deltas for lower arms/knees now follow probed hinge axes (same as walk). Soft hang + restoreHang reapply hinge-aware flex. IK pole flips if elbow lands forward of the arm chord.
2. **Snap/glitch into idle** — continuous `loco.gaitWeight` eases walk→hang; softer wrist spring while settling; restore no longer teleports free hands.

## Free OSS Godot map sources

Tracked in `godot/companion-world/maps/catalog.json` and fetchable via `scripts/fetch_map_pack.sh`:

| Pack | URL | License |
|------|-----|---------|
| Kenney 3D Platformer | github.com/KenneyNL/Starter-Kit-3D-Platformer | MIT + CC0 |
| Kenney City Builder | github.com/KenneyNL/Starter-Kit-City-Builder | MIT + CC0 |
| Kenney FPS | github.com/KenneyNL/Starter-Kit-FPS | MIT + CC0 |
| KayKit Dungeon | github.com/KayKit-Game-Assets/KayKit-Dungeon-Remastered-1.0 | CC0 |
| Quaternius | quaternius.com | CC0 |
| Godot demos | github.com/godotengine/godot-demo-projects | MIT |

## Bundled loadable maps

`proto_arena`, `kenney_plaza` (vendored Kenney GLBs), `courtyard`, `mini_dungeon` — cycle with in-world **MAP** button / `WorldBridge.load_map`.
