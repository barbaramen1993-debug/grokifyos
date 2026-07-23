# Companion × Godot worlds

**Date:** 2026-07-21  
**Status:** Implemented (spike)  
**Module id:** `companion`  
**Project path:** `godot/companion-world`

## Goal

Drop our VRMs into real maps and walk them with the same third-person stick/jump feel as the Companion Web stage.

## What shipped

### Godot project (`godot/companion-world`)

| Piece | Status |
|-------|--------|
| Godot 4.7 project + `proto_arena` map | Done |
| `CharacterBody3D` player + spring-arm camera | Done |
| Touch stick + jump (same control contract as Web) | Done |
| `WorldBridge` autoload: `set_control_input` / `possess` / `load_map` | Done |
| Capsule placeholder actor (swap for VRM via godot-vrm) | Done |
| Android export preset `io.grokify.os.companion.world` | Done |
| Headless import smoke test | Passes |

### Android host

- `CompanionWorldLauncher` — opens installed World package
- Companion **Settings → World · Godot** button
- Package visibility query for `io.grokify.os.companion.world`

### Web stage fixes (same release)

- Limb **hinge probe** so knees/elbows flex correctly (no backwards bend)
- Pose wheel: bow / hips / cross use scripted body gestures (were look-only / dead)
- VRMA ownership while clip id is set (body IK no longer fights mid-load)

## Control contract (shared)

```text
set_control_input(move_x, move_y, jump, jump_edge)
possess(actor_id)
load_map(map_id)
```

Web: `CompanionStage.setControlInput` / `possess` / `registerActor`  
Godot: `WorldBridge` + `player_controller.gd`

## Run desktop

```bash
godot --path godot/companion-world
```

## Android World APK

1. Open `godot/companion-world` in Godot 4.7+
2. Install Android export templates + configure keystore
3. Export → Android → `io.grokify.os.companion.world`
4. Install APK on device
5. Companion → Settings → **Open Companion World**

## VRM + maps (next)

1. Asset Lib: **godot-vrm** + MToon (#2031)
2. Import Seed-san / user `.vrm` under `Player/Visual`
3. Kenney / Quaternius / KayKit GLB → new `maps/<id>.tscn` with colliders
4. Optional: Godot-as-library embed inside GrokifyOS (AAR) instead of side package

## Out of scope (still)

- Replacing Web chat/voice stage
- Multiplayer / V-Sekai social
- Runtime phone-side VRM import inside Godot (editor import first)
