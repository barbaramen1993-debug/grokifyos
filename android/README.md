# GrokifyOS Android

Native Kotlin + Jetpack Compose client for **https://grokifyos.grokpot.io** (self-hosted open-source twin of private Grokify).

Package id is **`io.grokify.os`** so it installs **alongside** private `io.grokpot.grokify` without overwriting it.

## Architecture

| Layer | Role |
|--------|------|
| **Dashboard** | `grokifyos.grokpot.io` — PHP, password-only admin auth |
| **PHP API** | Device tokens (`gos_…`), APK OTA, chat (`/api/me.php`, `/api/devices.php`, …) |
| **Bridge** | Dedicated WebSocket (`wss://…/grokify-ws/`) |
| **App** | This project — chat + permissions + background service |

## Server toolchain (this VPS)

| Tool | Location |
|------|----------|
| **JDK 17** | `/usr/lib/jvm/java-17-openjdk-amd64` (`JAVA_HOME`) |
| **Android SDK** | `/opt/android-sdk` (`ANDROID_HOME`) |
| **platform-tools (adb)** | `$ANDROID_HOME/platform-tools` |
| **SDK Platform 35** | `$ANDROID_HOME/platforms/android-35` |
| **Build-Tools 34/35** | `$ANDROID_HOME/build-tools/` |
| **Gradle wrapper** | `./gradlew` |

```bash
source /etc/profile.d/android-sdk.sh   # if present
```

## Build

```bash
cd /root/grokifyos/android
source /etc/profile.d/android-sdk.sh 2>/dev/null || true
./scripts/build.sh          # debug APK
# or
./gradlew :app:assembleDebug
```

Output:

`app/build/outputs/apk/debug/app-debug.apk`  
Package id (debug): `io.grokify.os.debug`

## Deploy to your phone

This host is a remote VPS — **USB ADB is not available here**. Use one of:

### A) Wireless ADB (same LAN, or reverse tunnel)

```bash
source /etc/profile.d/android-sdk.sh 2>/dev/null || true
cd /root/grokifyos/android
./scripts/install-device.sh PHONE_IP:PORT
```

### B) Download APK on the phone

1. Build + publish (below), or open the dashboard **Build** tab.
2. On phone: open the download URL → install (allow unknown sources once).

### C) Via your laptop

```bash
# on server
scp root@YOUR_SERVER:/root/grokifyos/android/app/build/outputs/apk/debug/app-debug.apk .
# on laptop with USB debugging
adb install -r app-debug.apk
```

## First launch

1. Sign in on https://grokifyos.grokpot.io (password admin).
2. Open **Devices** → create token (`gos_…`).
3. Paste token in the app.
4. Grant runtime permissions; open Notification access + battery unrestricted for background.

## OTA / publish

```bash
source /etc/profile.d/android-sdk.sh 2>/dev/null || true
cd /root/grokifyos/android
./scripts/publish.sh              # build debug + register as latest release
# or: ./scripts/publish.sh debug --no-build
```

Then open **https://grokifyos.grokpot.io** (logged in) → **Download APK**.

App checks `GET /api/update.php?version_code=N`, then can **Download & install**
via `GET /api/apk-download.php` with the device token. **versionCode must increase** each release.

## Defaults (BuildConfig)

| Field | Value |
|-------|--------|
| `API_BASE` | `https://grokifyos.grokpot.io/api` |
| `WS_URL` | `wss://grokifyos.grokpot.io/grokify-ws/` |
| `SITE_URL` | `https://grokifyos.grokpot.io` |
| `applicationId` | `io.grokify.os` |

## Package

`io.grokify.os` · minSdk 26 · targetSdk 35 · compileSdk 35 · AGP 8.7.3 · Kotlin 2.0.21
