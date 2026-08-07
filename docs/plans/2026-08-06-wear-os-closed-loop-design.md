# Wear OS closed loop — Design

**Date:** 2026-08-06  
**Status:** Approved (decisions locked)  
**Scope:** Bridge + server (PHP/API/schema) + Android host + new Wear OS target  
**Goal:** Develop and deploy a standalone Grokify Wear AI-assistant app from the same agent closed loop used for the phone — phone downloads the wear APK OTA and installs it on the watch; a phone inner app manages deploy and later views data the watch sends back.

## Locked product decisions

| Decision | Choice |
|----------|--------|
| Wear app identity | **Standalone** sideloadable APK (`standalone=true`), not a clone of the phone host |
| Initial wear UI | **Blank / minimal shell** — no port of phone apps; product direction is **AI assistant**, refined iteratively via chat |
| Build loop | Same as phone: **chat-driven** agent edit → build → publish |
| Phone role | Inner app **Watch Deploy** (name flexible): OTA download + install to watch **and** manage/view data the wear app reports |
| Deploy availability | **Release builds** (developer tooling, not debug-only) |
| Install transport | **Phone → watch OTA hop** (server wear channel → phone download → wireless ADB install), analogous to phone self-update |
| Install trigger v1 | User opens phone inner app and installs (auto-prompt when new wear channel version appears = v1.1) |

## Goal

Extend GrokifyOS so agents can:

1. Edit Wear OS code in-repo (blank shell → evolving AI assistant UX)  
2. Build a Wear APK on the host  
3. Publish it as a first-class release channel  
4. Have the **phone** OTA-download that APK and install it on the watch (wireless ADB)  
5. Use a **phone inner app** to manage that deploy path and, over time, surface data the watch app sends back  

…without breaking the existing phone host OTA path.

## Non-goals (v1)

- Play Store / Galaxy Store distribution  
- Samsung Health Sensor SDK / partner health APIs  
- Copying the phone host UI/app suite onto the watch  
- Shipping a finished AI-assistant UX on day one (direction only; iterate in chat)  
- Bundling a full agent runtime *on* the watch in v1  
- Full watch→phone telemetry pipeline in the first scaffold (design for it; implement after deploy loop works)  
- Automatic discovery of every Wear device brand — v1 targets **Galaxy Watch + wireless ADB**  
- Replacing Galaxy Wearable for pairing/setup

## Current foundation (what we reuse)

| Piece | Today | Wear impact |
|-------|--------|-------------|
| Agent closed loop | Grok Build → edit → build → publish | Same; add wear module + publish channel |
| `bridge_agent_cwd` | Agents can target different dirs | Point at `android/` or keep workspace root |
| Phone OTA | `grokify_apk_releases` + `update.php` + `ApkUpdater` | **Must not collide** with wear artifacts |
| `publish.sh` / `publish-apk.php` | Single phone APK pipeline | Multi-artifact (`channel`) |
| Wireless ADB scripts | Host-side `install-device.sh` | Phone-side ADB client to watch IP |
| Bluetooth / network on phone | Scanner apps, FGS, permissions | Discovery UI + connectivity for ADB hop |
| `AGENTS.md` | Phone version bump + publish | Separate wear release rules |

## Target architecture

```text
┌──────────────┐   agent edit/build    ┌─────────────────────┐
│ Phone chat   │ ────────────────────► │ Host (workspace)    │
│ (Grok Build) │                       │ :app  (phone host)  │
└──────────────┘                       │ :wear (standalone   │
                                       │        AI assistant │
                                       │        shell)       │
                                       └──────────┬──────────┘
                                                  │ publish.sh --channel phone|wear
                                                  ▼
                                       ┌─────────────────────┐
                                       │ PHP API + storage   │
                                       │ apk_releases by     │
                                       │ channel             │
                                       └──────────┬──────────┘
                                                  │ OTA check/download
                         ┌────────────────────────┼────────────────────┐
                         ▼                                             ▼
                  Phone host APK                              Wear APK (channel=wear)
                  (self-update via                                 │
                   ApkUpdater)                                     ▼
                                                          Phone inner app:
                                                          download wear OTA
                                                                   │
                                                                   ▼
                                                          Wireless ADB → Watch
                                                          (install -r)
                                                                   │
                                          later: watch data ───────┘
                                          → phone inner app (view/manage)
```

### Design principles

1. **Channel isolation** — phone and wear releases never share a single “latest” row.  
2. **Phone is the OTA install bridge** — wear APK is published like the phone APK; the phone applies it to the watch (watch does not self-update from the server in v1).  
3. **Wear is a separate product** — Gradle `:wear`, own `applicationId`, own versionCode stream; **not** a fork of `:app`.  
4. **Blank → iterate** — v1 watch surface is a minimal shell; AI-assistant UI/UX is discovered in-chat over time.  
5. **Phone inner app dual role** — deploy/manage the watch build **and** (over time) view data the wear app sends back.  
6. **Same agent UX** — `AGENTS.md` dual release rules; chat-driven builds like today.  
7. **Fail loud** — pairing/ADB/network failures surface as clear UI + agent-readable status.

---

## Piece 1 — Wear OS build target

### Layout

```text
android/
  settings.gradle.kts          # include(":app", ":wear")
  app/                         # existing phone host (unchanged role)
  wear/                        # NEW Wear OS application module
    build.gradle.kts
    src/main/
      AndroidManifest.xml      # wearable feature, standalone or companion flag
      java/io/grokify/os/wear/
      res/
  scripts/
    build.sh                   # build phone | wear | all
    publish.sh                 # --channel phone|wear
    publish-apk.php            # channel-aware
    install-device.sh          # optional --wear path (host-side)
```

### Module choices (recommended)

| Decision | Recommendation | Why |
|----------|----------------|-----|
| Module vs flavor | **Separate `:wear` module** | Different manifest, deps, UI toolkit, min constraints |
| applicationId | `io.grokify.os.wear` (+ `.debug` suffix) | Clean install side-by-side with phone; no package clash |
| UI | Wear Compose + Material for Wear | Standard, small-screen |
| Standalone | `com.google.android.wearable.standalone=true` for v1 | Install without Play companion packaging |
| minSdk | 30 (Wear OS 3+) | Galaxy Watch4+ / Watch9 class devices |
| compile/target | Align with phone (35) where possible | One SDK install on host |
| Network | Optional: talk to same `API_BASE` later | v1 can be offline/local-only shell |
| Shared code | Optional `:core` later | **Defer** — copy minimal models only if needed |

### Wear app product direction

| Horizon | What ships |
|---------|------------|
| **v1 scaffold** | Intentionally **blank shell**: app id, Wear Compose empty home (or near-empty), **versionName/versionCode** visible so install success is verifiable. No phone-host feature port. |
| **Near-term** | Evolve toward an **on-wrist AI assistant** — UI/UX TBD in chat (tiles, voice, glanceable replies, etc.). |
| **Phone companion inner app** | Not a second copy of the wear UI: **manage OTA deploy**, connection/status, and **view/manage data the wear app sends back** as that pipeline is built. |

Agents grow the wear surface the same way they grow phone features — via this chat loop — without a fixed final UI up front.

### Manifest essentials

- `uses-feature android:name="android.hardware.type.watch"`  
- `com.google.android.wearable.standalone=true`  
- Wear library dependency (`androidx.wear:wear`, compose-wear)  
- Foreground services only if needed later (battery-sensitive)  
- Keep permissions minimal for v1 (none beyond network when the assistant needs API access)

### Build commands

```bash
# Phone (existing)
./gradlew :app:assembleDebug

# Wear
./gradlew :wear:assembleDebug
# → android/wear/build/outputs/apk/debug/wear-debug.apk
```

Extend `android/scripts/build.sh`:

```bash
./scripts/build.sh debug          # phone only (compat)
./scripts/build.sh debug wear     # wear only
./scripts/build.sh debug all      # both
```

---

## Piece 2 — Publish path for watch APKs

### Schema

New migration `schema/003_apk_channel.sql`:

```sql
ALTER TABLE grokify_apk_releases
  ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'phone'
    AFTER version_name;

-- Drop unique on version_code alone; uniqueness is per channel
ALTER TABLE grokify_apk_releases
  DROP INDEX uq_grokify_apk_version_code;

ALTER TABLE grokify_apk_releases
  ADD UNIQUE KEY uq_grokify_apk_channel_version (channel, version_code);

ALTER TABLE grokify_apk_releases
  ADD KEY idx_grokify_apk_channel_active (channel, is_active, version_code);
```

Valid channels: `phone` | `wear` (extensible later: `tv`, etc.).

**Version codes:** independent streams. Phone `254` and wear `3` are unrelated.  
**Activation:** deactivating “latest” is **per channel** (do not deactivate phone when publishing wear).

### Storage naming

```text
storage/apk/phone/grokifyos-v{code}-{name}.apk   # optional subdir migration
storage/apk/wear/grokifyos-wear-v{code}-{name}.apk
```

v1 may keep flat `storage/apk/` with channel prefix in filename if simpler:

```text
grokifyos-phone-v254-0.1.254.apk
grokifyos-wear-v3-0.1.3.apk
```

### PHP API

| Endpoint | Change |
|----------|--------|
| `gos_latest_apk(?string $channel = 'phone')` | Filter by channel |
| `gos_register_apk_upload(..., channel: 'phone'\|'wear')` | Deactivate only same channel |
| `GET /api/update.php?channel=phone\|wear&version_code=` | Return latest for channel |
| `GET /api/apk-download.php?channel=…` | Stream correct artifact |
| Dashboard / status / me | Expose both latest phone + wear summaries |

**Backward compatible:** missing `channel` ⇒ `phone`.

### Scripts

`publish.sh`:

```bash
./scripts/publish.sh debug --channel phone --changelog "…"
./scripts/publish.sh debug --channel wear  --changelog "…"
```

`publish-apk.php`:

- `--channel=phone|wear`  
- `--from-debug` / `--from-release` resolve path from channel  
- Read version from `android/app/build.gradle.kts` **or** `android/wear/build.gradle.kts`  
- Refuse cross-channel version auto-bump confusion

### AGENTS.md rules (both targets)

```markdown
## Android phone releases
1. Bump android/app versionCode/versionName
2. ./scripts/publish.sh debug --channel phone --changelog "…"

## Wear OS releases
1. Bump android/wear versionCode/versionName
2. ./scripts/publish.sh debug --channel wear --changelog "…"
3. Phone Watch Deploy (or agent-triggered host path) installs to watch
```

---

## Piece 3 — Phone → Watch install bridge

This is the hard operational piece. v1 strategy: **wireless ADB**.

### Prerequisites (user / device)

On Galaxy Watch (Developer options):

1. ADB debugging **on**  
2. Debug over Wi‑Fi **on** (note IP:port, often `:5555`)  
3. Watch and phone on **same LAN** (or routable network)  
4. Accept RSA fingerprint on first connect (one-time UX)

### Phone capabilities needed

| Capability | Use |
|------------|-----|
| Internet / LAN | Download wear APK from server; TCP to watch ADB |
| Storage/cache | Hold wear APK under `cache/apk/wear-….apk` |
| Foreground service | Long install / transfer without process death |
| Optional Bluetooth | UX: discover nearby watch name/MAC (not the install transport in v1) |

### ADB on phone options (decision)

| Option | Pros | Cons | v1 pick? |
|--------|------|------|----------|
| **A. Bundle `adb` binary + lib** (or Termux-style) | Full feature parity with host script | Binary size, SELinux, arch (arm64), Play policy N/A for sideload host | **Primary** |
| **B. Pure-Java/Kotlin ADB client library** | No native bin | Less common, maintenance risk | Fallback research |
| **C. Server installs via host adb** | Simple code | Requires watch reachable from VPS — usually **false** | Reject |
| **D. Manual “share APK” only** | Easy | Breaks closed loop | Reject for goal |

**Recommendation:** Package a **static arm64 `adb`** (and required deps if any) under `android/app/src/main/jniLibs` or `assets/` extracted to `filesDir`, execute with `ProcessBuilder`. Gate behind a host module / settings screen. Document that this is a **dev deploy** tool, not a consumer Play feature.

### Host inner app: **Watch Deploy / Wear Manager** (`watch_deploy`)

Built-in phone app (same pattern as other inner apps). **Ships in release builds**, labeled as developer / device management tooling.

**Role (two halves)**

| Half | v1 | Later |
|------|----|--------|
| **Deploy** | OTA check/download wear channel + wireless ADB install (required for closed loop) | Auto-prompt when newer wear version published |
| **Manage / data** | Status: last installed wear version, connection target, install log | View data the wear app sends back (sensor samples, assistant events, sync inbox — schema TBD when wear features exist) |

**Screens / flows (v1 = deploy-first)**

1. **Watch target**  
   - IP:port field (persist last)  
   - “Connect” → `adb connect`  
   - Device list / `adb devices` status  
2. **Wear OTA release** (mirror phone update UX, different channel)  
   - Poll `update.php?channel=wear` vs last-known installed wear versionCode  
   - Download via authenticated `apk-download.php?channel=wear`  
   - SHA-256 verify (reuse `ApkUpdater` patterns)  
3. **Install to watch**  
   - `adb -s ip:port install -r <apk>`  
   - Stream stdout/stderr to UI log  
   - Success → record version; optional `adb shell am start …`  
4. **History**  
   - Last install version, time, result  
5. **Data (stub)**  
   - Empty state: “Wear data will appear here as the watch app reports it”  
   - Reserve store/API surface so later wear→phone (or wear→server→phone) payloads have a home  

**State store:** `WatchDeployStore` (SharedPreferences / later DB): last host, last wear versionCode installed, auto-check toggle, optional cached wear payloads.

**API reuse:** extend `ApkUpdater` with `channel` parameter rather than duplicating download logic:

```kotlin
apkUpdater.download(
  downloadUrl = "$API_BASE/apk-download.php?channel=wear",
  expectedSha256 = …,
)
// then WatchAdbClient.install(serial, file)
```

**Wear → phone data path (design only until assistant features need it)**

- Prefer simple, explicit channels as features land: e.g. watch POST to server with device auth, phone inner app polls; and/or nearby message API / shared network once both apps talk to the same backend.  
- Do **not** block the deploy loop on this path.

### Security notes

- Device Bearer token still required to download wear APK  
- Do not log tokens in ADB UI  
- Treat adb RSA prompts: surface “check watch for allow”  
- **Available in release** with clear “developer deploy / watch manager” labeling (not debug-gated)

### Host-side fallback (dev machine)

Keep/extend `install-device.sh`:

```bash
./scripts/install-device.sh --wear 192.168.1.40:5555
```

Useful when phone bridge is broken; not the primary mobile loop.

---

## Piece 4 — Bridge + agent guidance

### Bridge changes (minimal)

Bridge already:

- Runs agents with selectable `cwd`  
- Does not hardcode phone-only publish  

**v1 bridge code changes:** mostly none, unless we add:

| Optional enhancement | Value |
|----------------------|-------|
| Inject system note when cwd is under `android/wear` | Helps agent pick wear publish path |
| Expose `GET /status` fields: latest phone + wear codes from DB | Agents can verify publish |
| Tooling docs only | Often enough |

**Do not** special-case agent process launching per target in v1 — keep one agent, different instructions + paths.

### AGENTS.md / system notes

Add a dedicated section (and optionally a `system_chat_notes` seed) covering:

- When user says “watch” / “wear” → edit `:wear`, bump **wear** versions, publish `--channel wear`  
- When user says “app” / “phone” → existing phone path  
- After wear publish, remind that **phone must run Watch Deploy** (or agent can only publish; install is on-device)  
- Wear constraints: small UI, battery, no heavy FGS, no phone-only APIs  

### Working directory

Default remains repo root (`GROKIFY_WORKSPACE`). Agents already navigate multi-module trees. Optional: settings preset “Wear focus” that sets cwd to `android/` — not required for v1.

---

## Piece 5 — End-to-end flow (definition of done)

### Happy path

1. User (on phone chat): “Nudge the wear shell UI toward a simple assistant home”  
2. Agent edits only `android/wear/…` (not a copy of phone apps), bumps wear `versionCode`  
3. Agent runs `cd android && ./scripts/publish.sh debug --channel wear --changelog "…"`  
4. Server stores wear release; phone channel unchanged  
5. User opens phone **Watch Deploy** inner app  
6. Phone OTA-downloads wear APK; wireless ADB `install -r` to watch  
7. Watch shows updated shell (version / new UI as iterated)  
8. (Later) Wear app reports data → phone inner app shows it  

### DoD checklist

- [ ] `:wear` assembles blank-shell APK (version visible)  
- [ ] Publishing wear does not unpublish phone  
- [ ] `update.php?channel=wear` returns wear-only latest  
- [ ] Phone release build includes Watch Deploy; can download wear APK with device token  
- [ ] Phone can install to Watch9 over wireless ADB at least once in real test  
- [ ] `AGENTS.md` documents both channels + “blank AI-assistant wear, iterate in chat”  
- [ ] Phone host OTA regression: phone update still works  

---

## Realistic caveats (accepted)

| Risk | Mitigation |
|------|------------|
| Wireless ADB flaky (sleep, Wi‑Fi, IP change) | Persist last IP; reconnect button; wake-watch guidance; optional mDNS later |
| First-time RSA auth | UI copy + retry |
| Bundled adb binary maintenance | Pin version; document update path; arch = arm64-v8a only first |
| Watch battery / background limits | Wear app stays light; no agent on watch |
| Signing | Debug keystore for loop; release signing later (same as phone) |
| versionCode confusion | Strict channel separation + agent docs |
| Samsung Health sensors | Out of scope; document as future partner track |

---

## Phased delivery

### Phase A — Scaffold (build only)

- `:wear` module, **blank shell** + version label, assembleDebug  
- `build.sh` wear target  
- Docs stub  

### Phase B — Multi-channel publish (server)

- Schema `003`  
- `gos_latest_apk($channel)` + register/deactivate per channel  
- `update.php` / `apk-download.php` / `publish-apk.php` / `publish.sh`  
- Storage naming  

### Phase C — Phone Watch Deploy (release-enabled)

- `ApkUpdater` channel support  
- Bundled adb client wrapper  
- Watch Deploy inner app: OTA download + install + data stub screen  
- Manual E2E on Watch9  

### Phase D — Agent polish

- `AGENTS.md` dual path + wear product notes (standalone AI assistant, iterate UI in chat)  
- Optional system note / status API fields  
- README architecture section  

### Phase E — (Later) productize wear AI assistant + data loop

- Assistant UX on watch (chat-driven discovery)  
- Wear → phone/server data reporting; phone inner app inbox/views  
- Tiles/complications, better discovery as needed  
- Shared `:core` only if duplication hurts  
- Release signing for wear  

---

## Remaining open (non-blocking)

1. **adb transport only, or also Samsung device-manager APIs later?** → adb only for v1.  
2. **Auto-prompt on new wear channel version?** → v1.1 after manual install is solid.  
3. **Wear→phone data transport** (direct nearby vs server-mediated) → choose when first real payload exists.

---

## File touch map (expected)

| Area | Paths |
|------|--------|
| Wear module | `android/wear/**`, `android/settings.gradle.kts` |
| Scripts | `android/scripts/build.sh`, `publish.sh`, `publish-apk.php`, `install-device.sh` |
| Schema | `schema/003_apk_channel.sql` |
| API | `web/api/update.php`, `apk-download.php`, `status.php`, `me.php` |
| PHP helpers | `web/includes/system-chat.php` (`gos_latest_apk`, `gos_register_apk_upload`) |
| Phone app | `update/ApkUpdater.kt`, new `apps/watchdeploy/*`, `BuiltinPluginCatalog.kt`, `GrokifyAppRoot.kt` |
| Agent docs | `AGENTS.md`, `README.md`, `docs/ARCHITECTURE.md` |
| Bridge | optional status only; `bridge/server.js` if exposing release summary |

## Success metric

Using only phone chat + the phone Watch Deploy inner app, ship a visible change on a blank-shell Wear AI-assistant APK to a Galaxy Watch **without a cable and without a desktop**, via the same OTA-style publish path as the phone — then iterate UI/UX and watch-reported data from that foundation.
