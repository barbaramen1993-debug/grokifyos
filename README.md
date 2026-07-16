# GrokifyOS (open source)

**A mobile Android development kit** — self-hosted web control plane + native phone client — so you can **build custom versions of your own AI-powered phone** by working *through* the device, not just *on* it.

Think of it as **slipping the phone on for the phone**: you pair a real Android handset to *your* server, open hardware (camera, mic, GPS, Wi‑Fi, Bluetooth, notifications, media), stream agents via [Grok Build](https://grok.com), ship features as **built-in inner apps** in the host APK, and **publish APKs OTA** so the handset updates itself.

| | |
|--|--|
| **What it is** | Self-hosted Android MDK + AI assistant stack |
| **Clients** | Web dashboard (browser) · native app (`io.grokify.os`) |
| **Stack** | PHP 8.1+, MySQL/MariaDB, Node 18+ bridge, optional Android SDK |
| **Run mode** | Laptop / LAN **or** remote VPS with HTTPS |
| **Auth** | Username + password (web) · device Bearer tokens `gos_…` (phone) |
| **License** | [MIT](LICENSE) |

> **Not affiliated.** GrokifyOS is an independent, open-source project. It is **not** affiliated with, endorsed by, or sponsored by SpaceXAI/SpaceX/xAI, X (prev. Twitter), Grok, Grok Build, Mapbox, Spotify, or any related company. Product names above are trademarks of their respective owners; we only document how to use *your* accounts and APIs with *your* self-hosted stack.

---

## Why this exists

Most “AI phone” demos are a chat UI glued to an API. GrokifyOS is different:

1. **You own the host** — chat, devices, sessions, APKs, and secrets live on *your* machine or VPS.
2. **The phone is the runtime** — full permission model for real hardware: camera, microphone, location, nearby Wi‑Fi, Bluetooth, notifications, media session control.
3. **Inner apps are first-class** — Wi‑Fi / BT scanners, place notes, Spotify Live DJ, and maps ship as **built-in host modules** in the APK (no script sideloading).
4. **Grok Build is the builder** — agents run against *your* Grok Build login on the host; you (or another agent) edit the repo, rebuild, and **push OTA**.
5. **Closed loop** — change code → `publish.sh` → phone sees a new `versionCode` → install update → keep iterating without a cable.

Endless surface area: new Kotlin host modules, vault keys, maps, scanners, media, geofences — all under one paired device token.

---

## What you get

| Piece | Role |
|-------|------|
| **Web dashboard** | Login, chat sessions, notes, device pairing, APK release store |
| **REST APIs** | Auth, devices, chat, models, live Grok Build usage, OTA |
| **Agent bridge** | Node WebSocket gateway for streaming agents |
| **Android host** | Compose client: Chat · Settings · **Apps** hub · permission cards · OTA |
| **API key vault** | On-device keys for maps, Spotify, xAI voice |
| **Inner apps** | Built-in host modules in the APK (`BuiltinPluginCatalog`) |

Architecture overview: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**  
Full install (Ubuntu / Windows / macOS, TLS, bridge): **[docs/INSTALL.md](docs/INSTALL.md)**  
Android build / pair / OTA: **[android/README.md](android/README.md)**

---

## Quick start (humans)

```bash
git clone https://github.com/iBerry420/grokifyos.git
cd grokifyos
cp .env.example .env
# edit .env → MySQL credentials + secrets (see docs/INSTALL.md)

php scripts/install.php --admin=admin --password='your-long-password'

# local / LAN (phones on same Wi‑Fi: http://YOUR_LAN_IP:8787)
php -S 0.0.0.0:8787 scripts/dev-router.php
```

Open `http://127.0.0.1:8787` → sign in → **Devices** → mint a `gos_…` token → install the APK → paste the token.

For streaming agents + real usage numbers, log into Grok Build on the host and point the stack at that auth file (see [Grok Build](#grok-build-agents--usage) below).

---

## AI / Grok Build: clone and set up for a user

Paste this block (or point an agent at this section) when you want **Grok Build or another coding agent** to stand up GrokifyOS with minimal human steps.

### Agent checklist (idempotent)

```text
GOAL: Install GrokifyOS so a user can open the web UI and pair an Android app.

PREREQS (install if missing):
- PHP 8.1+ with pdo_mysql, curl, json, mbstring, session
- MySQL 8+ or MariaDB 10.5+ (dedicated database; do not share)
- Node.js 18+ (for agent bridge)
- git, openssl
- Optional: Android SDK + JDK 17 only if building APKs on this machine

STEPS:
1. Clone: git clone https://github.com/iBerry420/grokifyos.git && cd grokifyos
2. cp .env.example .env
3. Create MySQL database + user (utf8mb4). Put credentials in .env:
     GROKIFY_DB_HOST, GROKIFY_DB_PORT, GROKIFY_DB_NAME, GROKIFY_DB_USER, GROKIFY_DB_PASS
4. Generate secrets into .env:
     openssl rand -hex 32  → GROKIFY_WS_AUTH_SECRET
     openssl rand -hex 32  → GROKIFY_SECRETS_PEPPER
5. Set GROKIFY_SITE_URL:
     Local: http://127.0.0.1:8787  (or http://LAN_IP:8787 for phones)
     VPS:   https://user.domain
6. php scripts/install.php --admin=ADMIN_USER --password='LONG_PASSWORD'
7. Start web: php -S 0.0.0.0:8787 scripts/dev-router.php
8. Bridge (optional but recommended for streaming chat):
     cd bridge && npm ci && GROKIFY_BRIDGE_PORT=8876 node server.js
     Ensure GROKIFY_BRIDGE_URL / GROKIFY_BRIDGE_HEALTH / GROKIFY_WS_PATH match .env
9. Grok Build auth (for real agents + usage, not fake numbers):
     On host: grok login   (or grok login --device-code)
     ./scripts/sync-grok-auth.sh
     Verify: php scripts/check-grok-auth.php
10. NEVER commit: .env, storage/sessions/*, storage/apk/*, storage/grok-auth.json,
    android/local.properties, real API tokens, Mapbox pk. secrets, Spotify secrets.
11. Hand user:
     - Dashboard URL
     - Admin username
     - How to open Devices → create gos_… token
     - How to install APK (dashboard Download or android/scripts/publish.sh)
12. Android endpoints (if rebuilding APK): set API_BASE, WS_URL, SITE_URL in
    android/app/build.gradle.kts BuildConfig to the same host as GROKIFY_SITE_URL.

SUCCESS:
- GET /api/health.php reports ok
- Browser login works
- Device token can be created
- (If bridge + auth) chat streams and usage is non-empty or a clear auth error

DO NOT:
- Invent usage stats when auth.json is missing
- Bake secrets into the repo or mapbox_access_token.xml (there is no compile-time token file)
- Share MySQL with unrelated apps
```

### One-shot shell sketch for agents

```bash
set -euo pipefail
git clone https://github.com/iBerry420/grokifyos.git
cd grokifyos
cp .env.example .env

# Fill DB_* and SITE_URL in .env, then:
echo "GROKIFY_WS_AUTH_SECRET=$(openssl rand -hex 32)" >> .env
echo "GROKIFY_SECRETS_PEPPER=$(openssl rand -hex 32)" >> .env

php scripts/install.php --admin=admin --password='CHANGE_ME_LONG'
php -S 0.0.0.0:8787 scripts/dev-router.php &
( cd bridge && npm ci && GROKIFY_BRIDGE_PORT=8876 node server.js ) &
```

VPS + TLS + systemd examples: `deploy/`. Deep steps: **[docs/INSTALL.md](docs/INSTALL.md)**.

---

## Inner apps (built-in)

Open the Android app → **Apps** tab. Every app is a **native host module** compiled into the APK (`BuiltinPluginCatalog`). There is no script sideload / remote WebView plugin path — new apps ship by editing Kotlin and publishing a new APK OTA.

| App | What it does | Hardware / services | Keys |
|-----|----------------|---------------------|------|
| **Wi‑Fi Scanner** | Scan nearby networks; GPS pins, distance, times seen; alerts (SSID/MAC watch, unseen, strong nearby); Mapbox map of hits | Nearby Wi‑Fi, Location | **Mapbox** `pk.…` for maps |
| **Bluetooth Tracker** | BLE + classic discovery; GPS pins, distance, times seen; watch/unseen/strong alerts; map | Bluetooth, Location, Notifications | **Mapbox** for maps |
| **Place Notes** | Pin notes to GPS spots; on enter: notify, open an app, or show an image; list + map + area monitoring | Location, Notifications | **Mapbox** for maps |
| **Spotify** | Lockscreen / media controls; **Live AI DJ** booth (banter, queue chat); research/build/edit playlists via host Grok Build; optional Grok Voice TTS | Notifications, Media session, mic (voice), network | **Spotify Client ID** (+ optional secret); **SpaceXAI API key** for Grok Voice (device TTS works without it) |
| **SpaceXAI API Usage Analyzer** | Prepaid credit balance, period spend, soft/hard limits, 7‑day usage by model, balance history | Network | **SpaceXAI Management key** vault id `spacexai_management_key` (billing read on [management-api.x.ai](https://management-api.x.ai)) |

Capabilities are gated by Android permissions (Settings → Permissions, or in-chat `[[permission_request:…]]` cards). Keys live in **Settings → API key vault** on the device — never in git.

---

## Keys & tokens — how to get them

All third-party keys are **optional until you use the feature**. Store them **on the phone** in Settings (API key vault / Mapbox / Spotify cards). The server device token (`gos_…`) is separate: it only authenticates the app to *your* GrokifyOS host.

### 1. GrokifyOS device token (`gos_…`)

| | |
|--|--|
| **Where** | Web dashboard → **Devices** → create |
| **Used for** | Android API + WebSocket auth to *your* server |
| **Paste** | First-run / Settings on the phone |

### 2. Grok Build (server-side — agents + usage)

| | |
|--|--|
| **Where** | Host machine: [Grok / Grok Build CLI](https://grok.com) → `grok login` |
| **Wire-up** | `GROKIFY_GROK_AUTH_JSON=…` then `./scripts/sync-grok-auth.sh` → `storage/grok-auth.json` |
| **Used for** | Streaming agents, chat, playlist research, live usage chip |
| **Not** | Not the same as the on-device SpaceXAI API key |

```bash
grok login          # or: grok login --device-code
./scripts/sync-grok-auth.sh
php scripts/check-grok-auth.php
```

Missing auth → APIs return a **clear error** (no invented usage).

### 3. Mapbox public token (`pk.…`)

| | |
|--|--|
| **Where** | [mapbox.com](https://www.mapbox.com/) → Account → Access tokens → create a **public** token |
| **Paste** | Android **Settings → Mapbox** (or vault id `mapbox_access_token`) |
| **Used for** | Maps in Wi‑Fi Scanner, Bluetooth Tracker, Place Notes |
| **Note** | Vault-only. There is **no** baked-in `mapbox_access_token.xml` fallback. |

### 4. Spotify (Controller / Live DJ / playlists)

| | |
|--|--|
| **Where** | [developer.spotify.com](https://developer.spotify.com/) → Dashboard → Create app |
| **Client ID** | Paste in Settings / vault (`spotify_client_id`) |
| **Client secret** | Optional (PKCE works with Client ID alone); vault `spotify_client_secret` |
| **Redirect URI** | Must match what your app/build expects (default documented in-app; sample hosts use `https://…/spotify-callback.php`) |
| **OAuth tokens** | Access/refresh are stored **internally** after login — not typed by hand |

### 5. SpaceXAI keys (Voice TTS + Usage Analyzer)

| | Inference API key | Management key |
|--|--|--|
| **Where** | [console.x.ai](https://console.x.ai/) → **API Keys** | [console.x.ai](https://console.x.ai/) → **Management Keys** (billing read) |
| **Vault id** | `spacexai_api_key` (legacy `xai_api_key` auto-migrated) | `spacexai_management_key` |
| **Settings** | SpaceXAI API key card | SpaceXAI Management key card |
| **Used for** | Grok Voice TTS (`api.x.ai`) for Live DJ banter | Usage Analyzer prepaid balance / spend / limits (`management-api.x.ai`) |
| **Not used for** | Playlist research / main chat — those use **host Grok Build** + device token | same |

> These are **different product types**. Keep both filled if you use Voice and Usage Analyzer. Usage Analyzer prefers the Management key field; it may fall back to the inference vault only if Management is empty (pre-split installs).

---

## Develop → rebuild → OTA

Closed loop for custom forks of the phone app:

```bash
# bump versionCode / versionName in android/app/build.gradle.kts
cd android
./scripts/publish.sh debug --changelog "What changed"
# or: ./scripts/publish.sh release …
```

That builds the APK, registers it with your host’s APK store, and makes it downloadable for paired devices.

| Phone | Server |
|-------|--------|
| Checks `GET /api/update.php?version_code=N` | Serves newer release metadata |
| Downloads with device token | `GET /api/apk-download.php` |
| Installs update | `versionCode` must **increase** each ship |

Helpers: `android/scripts/build.sh`, `publish.sh`, `install-device.sh` (wireless ADB). Details: **[android/README.md](android/README.md)**.

Point Grok Build at this repo on the **same host** (or a remote with deploy access) so agents can edit Kotlin/PHP, run `publish.sh`, and the handset picks up the new build without USB.

---

## Repository layout

```text
web/           PHP app (public UI, API, includes, assets)
schema/        SQL schema (greenfield install)
bridge/        Node WebSocket agent gateway
android/       Kotlin + Compose host (io.grokify.os) + inner apps
scripts/       install.php, dev router, grok-auth helpers
deploy/        Apache vhost + systemd unit examples
docs/          install + architecture
storage/       sessions, bridge runtime, APKs (gitignored contents)
uploads/       chat media (gitignored)
```

---

## Auth model

| Client | Method |
|--------|--------|
| Browser | Username + password → session cookie `__grokifyos_sid` |
| Android | Device Bearer `gos_…` minted in the web UI after login |
| Bridge WS | Shared `GROKIFY_WS_AUTH_SECRET` + device/session tokens as designed |

Password-only admin auth keeps self-hosting simple. Optional OAuth can be added later as config — not required to run.

---

## Grok Build (agents + usage)

```env
GROKIFY_GROK_AUTH_JSON=/path/to/auth.json   # from `grok login`
```

Prefer the synced copy for PHP-FPM readability:

```bash
./scripts/sync-grok-auth.sh    # → storage/grok-auth.json + .env update
```

Usage endpoints call billing with **your** credentials only. No phone-home to a central GrokifyOS SaaS.

---

## Security

- Never commit `.env`, `storage/sessions/*`, `storage/apk/*`, `storage/grok-auth.json`, or real vault keys
- Use HTTPS on any host reachable from the public internet
- Strong DB password, `GROKIFY_WS_AUTH_SECRET`, and `GROKIFY_SECRETS_PEPPER`
- Keep `storage/` writable only by the web/bridge user
- Treat Mapbox `pk.` / Spotify / xAI keys as secrets even when “public” client tokens

---

## Contributing

Issues and PRs welcome. Prefer small, focused changes. Keep secrets out of the tree. If you add an inner app, implement it as a built-in host module (`BuiltinPluginCatalog` + Compose pane) and document its capabilities and required vault key ids in this README.

---

## Changelog

Android host versions (`versionName` / `versionCode` in `android/app/build.gradle.kts`). Newest first. OTA notes on the phone come from `publish.sh --changelog`; this section is the longer human history.

### 0.1.110 — Live DJ: More like this mix (similar-first)

- **More like this** no longer dumps mostly same-artist tops. Batch is mixed (~¼ same-artist deep cuts, ~½ related artists, rest genre / playlist-radio / your liked+tops blend).
- Related artists expanded (more seeds, random depth), album B-sides for same-artist variety, genre tags from the seed, public playlist radio searches.
- Artist-diverse pick + interleaved order so you don’t get the same name three times in a row; chat lists `[same]` / `[related]` / `[genre]` / `[mix]` tags.

### 0.1.109 — Apps tab: last-app icon & name

- When you’re on **Home / Chat / Update** and a mini-app was last open, the **Apps** tab shows that app’s **icon + short name** (resume on tap).
- While **inside** a mini-app, the tab switches back to **Apps** (grid) so one tap returns to the hub drawer (replaces double-tap).

### 0.1.108 — Live DJ: more-like-this summary + prompt templates

- **More like this**: sticky “Finding…” indicator clears when done; system chat lists every added track (no talk).
- **Prompt templates** in Live DJ → Settings: research angles (enable for random pack), behaviors (pick / edit / add custom), plus editable banter / research / chat system prompts with placeholders.

### 0.1.107 — Live DJ: More like this

- **More like this** on current + past chat tracks (and Control transport): same artist top cuts + related-artist radio, **prepended** to UP NEXT so they play next.
- Listener-attributed queue reason so banter can credit the request correctly.

### 0.1.106 — Live DJ: banter cadence + queue attribution

- **Talk only when due**: prefetched banter no longer forces speech every handoff — countdown / Skip+talk only.
- **No double-count** on play landing: silent handoffs and late Spotify syncs no longer both increment the banter counter (was accelerating to “every song”).
- **Who queued it**: DJ radio picks (liked/top/artist/genre) are labeled LIVE DJ; only chat/request cuts count as LISTENER. Prompts forbid “you queued this” on AI-picked tracks.
- Keep original pool `reason` on AI set picks (don’t overwrite with banter notes that looked like user requests).

### 0.1.105 — Live DJ chat: like past songs

- **Heart on previously played tracks** in DJ booth chat (not only now-playing) — saves/removes from Spotify Liked Songs.
- Batch liked-status lookup for chat history so hearts reflect library state when you scroll back.

### 0.1.104 — Live DJ: inter-song buffer (no false pause)

- **Between-track grace**: empty / not-playing flickers after a handoff or play no longer freeze auto-handoff as “paused / idle”.
- **Mid-pause debounce**: requires ~4.5s of sustained mid-track pause before treating it as a real user pause (Spotify often reports paused while buffering the next cut).
- **Longer empty-player buffer** before idle advance or session-hold; sticky `wasPlaying` through end-of-track blips so the set keeps moving.

### 0.1.103 — Live DJ: your name + rotating research

- **Your name** in Live DJ settings (manual, or **From Spotify** display name). Auto-fills once when empty so the DJ can address you on mic.
- **Name ≠ city**: prompts hard-separate listener name from metro/city so banter never greets you as your location.
- **Random research angles** each talk: lyrics & meaning · album/song facts · artist facts · shows & tours (local + national) · recent X/social · classic radio host color — 1–3 angles per cycle so packs stay varied.
- **Unhinged / Hype Unhinged**: stronger taste roasts using the current set + research pack (still no protected-class slurs).

### 0.1.102 — Spotify re-authorize without logout

- **Settings → Spotify** and **Spotify → Account**: **Re-authorize** while still connected (forces consent dialog for full scopes, including Liked Songs / library modify).
- Like / library permission errors surface a **Re-authorize Spotify** CTA instead of a dead-end message.
- OAuth helper: `SpotifyOAuth.reauthorize()` with `show_dialog=true` so scope upgrades don’t require logout.

### 0.1.98–0.1.101 — Live DJ: genre board, behavior modes, richer research

- **Genre board** (optional, multi-select): chips discovered from your Spotify top artists; biases queue building and banter context when set.
- **Behavior modes** (tone after research + queueing): Default · Hype · Hype Unhinged · Comedy · Soothing · Unhinged.
- **Listener city**: set your metro so research can surface **upcoming shows** and lightly inject tracks from artists touring near you (including artists you already listen to).
- **Deeper on-air research**: lyrics themes (current + next), album / release context, better artist–song–album facts, show/tour bullets — still tool-backed via host Grok, no full lyric dumps.
- Banter / handoff polish: pre-bake TTS (`synthesize_only` / `audio_path`) for smoother talkovers; sine-style banter frequency updates retained under Default and friends.

### 0.1.95–0.1.97 — Maps + Place Notes

- **Leaflet assets** shipped in-app (`android/app/src/main/assets/map/`) so the WebView map shell loads offline-friendly; basemap still uses your Mapbox vault token.
- Shared **WifiMapView** upgrades: radius rings, tap-to-pin (`onMapTapped`), auto-fit control, empty-state hints, place-friendly popups.
- **Place Notes**: labeled **List | Map** toggle; map of all places with radius rings; editor map preview with GPS + tap-to-set pin.

### 0.1.94 and earlier (highlights)

| Version | Notes |
|---------|--------|
| **0.1.94** | Live DJ: hold auto-handoff on pause; double-tap Apps hub |
| **0.1.92** | Mapbox maps fix; BT/Wi‑Fi scan persistence |
| **0.1.91** | Live DJ booth mode (chat/queue/play without auto-handoff) |
| **0.1.90** | Live DJ direct-play (stop using Spotify’s queue as source of truth) |
| **0.1.88–0.1.89** | Queue ↔ Spotify Up Next alignment; less thrash mid-song |
| **0.1.87** | One-tap Grok/xAI device OAuth when usage needs re-login |
| **0.1.83–0.1.86** | Native queue sync, resume after restart, drift fixes, 1:1 mirror |
| **0.1.79–0.1.80** | SpaceXAI Management key split; usage history/threshold fixes |
| **0.1.76** | Chat stick-to-bottom + bubble menus |

Also in this tree (chat UI): markdown **tappable links** (markdown + bare `https://` / `www.` URLs; safe href allowlist).

When you ship a new APK, **bump** `versionCode` / `versionName`, publish OTA, then add a short bullet block under a new `### 0.x.y` heading at the top of this list.

---

## Disclaimer

**GrokifyOS is not affiliated with SpaceXAI/xAI/SpaceX, Grok, Grok Build, Mapbox, Spotify, or any other third party.**  
It is a community MIT project that lets you self-host tooling which *may* call third-party APIs using credentials **you** provide. You are responsible for complying with each provider’s terms of service and for securing your own deployment.

---

## License

[MIT](LICENSE) — use it, fork it, host it, ship your own custom phone OS.
