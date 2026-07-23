# Companion World (Godot 4)

Third-person play space for Grokify Companion — same stick/jump language as the Web VRM stage, with swappable free/open-source maps.

## Open / run

```bash
godot --path godot/companion-world
# headless smoke:
godot --path godot/companion-world --headless --quit-after 2
```

## Bundled maps

| Id | What |
|----|------|
| `proto_arena` | Flat debug pad |
| `kenney_plaza` | Kenney CC0 platforms (vendored GLBs) |
| `courtyard` | Primitive plaza (KayKit/Quaternius stand-in) |
| `mini_dungeon` | Multi-room dungeon layout |

Tap **MAP** in-game (or `WorldBridge.next_map()` / `load_map("id")`) to cycle.

Catalog of free repos: [`maps/catalog.json`](maps/catalog.json)

## Fetch more free map packs

```bash
./scripts/fetch_map_pack.sh list
./scripts/fetch_map_pack.sh kenney-platformer   # already vendored
./scripts/fetch_map_pack.sh kaykit-dungeon      # KayKit CC0 dungeon
./scripts/fetch_map_pack.sh kenney-city
./scripts/fetch_map_pack.sh kenney-fps
```

Then open Godot once to reimport GLBs, instance them in a new `maps/<id>.tscn`, and append the id to `WorldBridge.MAP_IDS` + `maps/catalog.json`.

### Free open-source sources we track

| Repo | License | Notes |
|------|---------|--------|
| [KenneyNL/Starter-Kit-3D-Platformer](https://github.com/KenneyNL/Starter-Kit-3D-Platformer) | MIT + CC0 | Platforms used by `kenney_plaza` |
| [KenneyNL/Starter-Kit-City-Builder](https://github.com/KenneyNL/Starter-Kit-City-Builder) | MIT + CC0 | City blocks |
| [KenneyNL/Starter-Kit-FPS](https://github.com/KenneyNL/Starter-Kit-FPS) | MIT + CC0 | Interiors |
| [KayKit-Dungeon-Remastered](https://github.com/KayKit-Game-Assets/KayKit-Dungeon-Remastered-1.0) | CC0 | 200+ dungeon props |
| [kenney.nl/assets](https://kenney.nl/assets) | CC0 | All Kenney packs |
| [quaternius.com](https://quaternius.com/) | CC0 | Modular nature/buildings |
| [godot-demo-projects](https://github.com/godotengine/godot-demo-projects) | MIT | Official demos |

## Layout

| Path | Role |
|------|------|
| `scenes/main.tscn` | World + camera + touch HUD |
| `scenes/player.tscn` | `CharacterBody3D` actor |
| `maps/*.tscn` | Loadable maps |
| `maps/vendor/` | Fetched third-party CC0 assets |
| `scripts/world_bridge.gd` | Autoload: possess / control / load_map / next_map |
| `scripts/fetch_map_pack.sh` | Clone free packs into vendor/ |

## Control contract (matches Web)

```text
set_control_input(move_x, move_y, jump, jump_edge)
possess(actor_id)
load_map("kenney_plaza")
list_maps() → PackedStringArray
next_map()
```

## Android (in-app — preferred)

Maps ship **inside** GrokifyOS. No separate Godot APK for remote OTA work.

```bash
# After adding/fetching GLBs:
./scripts/sync_to_android.sh
# Then rebuild/publish the main app (version bump + publish.sh)
```

- Assets land in `android/app/src/main/assets/companion/world/`
- Companion → Settings → **Open Companion World** (or on-stage **MAP**)
- Stage API: `CompanionStage.enterWorld` / `loadMap` / `nextMap`

## Android (optional side package)

- Export preset `Android` → package `io.grokify.os.companion.world`
- Legacy path; not required once maps are synced into the main APK
