# GrokifyOS Android

Native Kotlin + Jetpack Compose client for your self-hosted **GrokifyOS** server.

Package id: **`io.grokify.os`** (debug: `io.grokify.os.debug`).

## Architecture

| Layer | Role |
|-------|------|
| **Dashboard** | Your host — PHP, password admin auth |
| **PHP API** | Device tokens (`gos_…`), APK OTA, chat |
| **Bridge** | WebSocket agents (`wss://…/grokify-ws/` or LAN `ws://…`) |
| **App** | Chat, permissions, background service |

## Requirements

| Tool | Notes |
|------|--------|
| **JDK 17** | `JAVA_HOME` |
| **Android SDK** | `ANDROID_HOME` — platform 35, build-tools 34+ |
| **Gradle wrapper** | `./gradlew` / `gradlew.bat` |

## Configure API endpoints

In `app/build.gradle.kts` (BuildConfig), set your host before building:

| Field | Local example | VPS example |
|-------|---------------|-------------|
| `API_BASE` | `http://192.168.1.10:8787/api` | `https://your.domain/api` |
| `WS_URL` | `ws://192.168.1.10:8787/grokify-ws/` * | `wss://your.domain/grokify-ws/` |
| `SITE_URL` | `http://192.168.1.10:8787` | `https://your.domain` |

\* WebSocket through the PHP dev server may need a separate bridge port or proxy; production uses reverse-proxy to the Node bridge.

## Build

```bash
cd android
./gradlew :app:assembleDebug          # Linux / macOS
# gradlew.bat :app:assembleDebug      # Windows
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Helpers (Linux/macOS):

```bash
./scripts/build.sh
./scripts/publish.sh                  # build + register release on server
./scripts/install-device.sh IP:PORT   # wireless adb
```

## Install on a phone

1. Open your GrokifyOS dashboard → log in.
2. **Devices** → create token (`gos_…`).
3. Install APK via USB, wireless ADB, or dashboard **Download APK**.
4. Paste token. Runtime permissions (camera, mic, location, …) are **not** requested on first launch.
5. **Settings → Permissions** — toggle each capability when you need it. Grok can also push an in-chat **Allow / Not now** card via `[[permission_request:camera|reason]]` markers (or a `permission_request` WS event).
6. **Settings → Notification access** — enable GrokifyOS so Grok can pull your active notifications (also toggle **Share with Grok** in app Settings).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## OTA

Server: `GET /api/update.php?version_code=N` then `GET /api/apk-download.php` with the device token.  
**versionCode must increase** each release you publish.

## Package

`io.grokify.os` · minSdk 26 · targetSdk 35 · compileSdk 35 · AGP 8.7.3 · Kotlin 2.0.21
