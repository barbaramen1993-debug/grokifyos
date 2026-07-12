# Grokify Android

Native Kotlin + Jetpack Compose client for **https://grokify.grokpot.io**.

## Architecture

| Layer | Role |
|--------|------|
| **Dashboard** | `grokify.grokpot.io` — PHP, dark mobile UI, admin session (Option A) |
| **PHP API** | Device tokens, APK OTA, status (`/api/grokify-*.php`) |
| **Bridge** | Same WebSocket as admin System Chat (`wss://…/grokpot-ws/`) |
| **App** | This project — chat + hardware permissions + background service |

## Server toolchain (already installed on grokpot)

| Tool | Location |
|------|----------|
| **JDK 17** | `/usr/lib/jvm/java-17-openjdk-amd64` (`JAVA_HOME`) |
| **Android SDK** | `/opt/android-sdk` (`ANDROID_HOME`) |
| **platform-tools (adb)** | `$ANDROID_HOME/platform-tools` |
| **SDK Platform 35** | `$ANDROID_HOME/platforms/android-35` |
| **Build-Tools 34/35** | `$ANDROID_HOME/build-tools/` |
| **Gradle 8.11.1** | `/opt/gradle` + project wrapper `./gradlew` |

Env is loaded from `/etc/profile.d/android-sdk.sh` (new shells). Current shell:

```bash
source /etc/profile.d/android-sdk.sh
```

## Build

```bash
cd /root/grokpot/android
source /etc/profile.d/android-sdk.sh
./scripts/build.sh          # debug APK
# or
./gradlew :app:assembleDebug
```

Output:

`app/build/outputs/apk/debug/app-debug.apk`  
Package id (debug): `io.grokpot.grokify.debug`

## Deploy to your phone

This host is a remote VPS — **USB ADB is not available here**. Use one of:

### A) Wireless ADB (same LAN, or reverse tunnel)

On the phone (Developer options):

1. Enable **Wireless debugging**
2. Note the IP:port (or pair code for Android 11+)

From this server (phone must be reachable, or use an SSH reverse tunnel from your laptop):

```bash
source /etc/profile.d/android-sdk.sh
cd /root/grokpot/android
./scripts/install-device.sh PHONE_IP:PORT
```

Pairing (first time, Android 11+):

```bash
adb pair PHONE_IP:PAIR_PORT   # enter pair code
adb connect PHONE_IP:PORT
./scripts/install-device.sh
```

### B) Download APK on the phone

1. Build, then publish from the Grokify **Build** tab (or copy APK under a web path).
2. On phone: open the download URL → install (allow unknown sources for the browser once).

### C) Via your laptop

```bash
# on server
scp root@YOUR_SERVER:/root/grokpot/android/app/build/outputs/apk/debug/app-debug.apk .
# on laptop with USB debugging
adb install -r app-debug.apk
```

## First launch

1. Sign in on https://grokpot.io (admin with System Chat permission).
2. Open https://grokify.grokpot.io → **Devices** → create token (`gf_…`).
3. Paste token in the app.
4. Grant runtime permissions; open Notification access + battery unrestricted for background.

## OTA

### Publish to website (phone download)

```bash
source /etc/profile.d/android-sdk.sh
cd /root/grokpot/android
./scripts/publish.sh              # build debug + register as latest release
# or: ./scripts/publish.sh debug --no-build   # re-publish last build only
```

Then open **https://grokify.grokpot.io** (logged in) → **Download APK** on Home or Build.

App checks `GET /api/grokify-update.php?version_code=N`, then can **Download & install**
in the Update tab (`GET /api/grokify-apk-download.php` with device token → system installer).
First time Android may ask to allow “Install unknown apps” for Grokify.
Publish from dashboard Build tab; **versionCode must increase** each release.

## Permissions (declared)

Camera, mic, speech, precise location (+ background), Bluetooth scan/connect, Wi‑Fi nearby, media/files, notifications listener, foreground services, install packages (OTA), Android Auto / Assist entry points.

## Package

`io.grokpot.grokify` · minSdk 26 · targetSdk 35 · compileSdk 35 · AGP 8.7.3 · Kotlin 2.0.21
