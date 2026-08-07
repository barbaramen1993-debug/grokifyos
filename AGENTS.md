# GrokifyOS agent notes

## Android releases (always)

GrokifyOS has **three** APK channels. Never mix them.

### Phone host (`channel=phone`, module `:app`)

After any **phone** app change the user can install:

1. Bump `versionCode` / `versionName` in `android/app/build.gradle.kts`.
2. Publish the OTA APK so the in-app updater can pick it up:

```bash
cd android && ./scripts/publish.sh debug --channel phone --changelog "short notes"
# default channel is phone if --channel is omitted:
cd android && ./scripts/publish.sh debug --changelog "short notes"
```

### Wear OS app (`channel=wear`, module `:wear`)

Standalone AI-assistant + radial telemetry HUD — **not** a UI clone of the phone host. UI/UX is iterated in chat.

**Package note:** Wear `applicationId` must be **`io.grokify.os`** (same as phone, with the same debug suffix). Wear Data Layer only syncs between identical package name + signing cert. Kotlin `namespace` stays `io.grokify.os.wear`.

After any **wear** app change:

1. Bump `versionCode` / `versionName` in `android/wear/build.gradle.kts` (independent stream from phone).
2. Publish wear channel:

```bash
cd android && ./scripts/publish.sh debug --channel wear --changelog "short notes"
```

3. On the phone, open **Apps → Watch Deploy** → set Connect IP:port → **Update & install** (one tap: check + download + install). Use **Cancel** if an install hangs.

### Wear watch face (`channel=wear-face`, module `:wear-face`)

**Watch Face Format** package (`applicationId = io.grokify.os.wear.face`) — resource-only (`hasCode=false`). Must stay a **separate APK** from `:wear` (Wear OS / Play requirement).

The interactive radial HUD lives in `:wear`. The face is the always-on WFF twin (time + system complications for HR/steps).

After any **wear-face** change:

1. Bump `versionCode` / `versionName` in `android/wear-face/build.gradle.kts`.
2. Publish:

```bash
cd android && ./scripts/publish.sh debug --channel wear-face --changelog "short notes"
```

3. Install on watch via wireless ADB (`adb install -r`). Watch Deploy currently targets `channel=wear` for the app; face can be installed the same way once UI supports the channel (or adb from phone/host).

Do this by default for shippable Android work — do not wait to be asked again.

### Build helpers

```bash
cd android
./scripts/build.sh debug phone      # :app only
./scripts/build.sh debug wear       # :wear only
./scripts/build.sh debug wear-face  # :wear-face only
./scripts/build.sh debug all        # phone + wear + wear-face
```

### Watch Deploy (phone inner app)

- Release-enabled developer tooling on the phone.
- Downloads `channel=wear` via the same OTA API as phone self-update.
- Installs to Galaxy Watch over **wireless ADB** (bundled arm64 `libadb.so`).
- **Data** tab is a stub until the wear app reports payloads.

### Wear product notes

- Radial breathing HUD gathers: time, HR, steps, compass, location, weather (Open-Meteo), battery, media/messages (notification listener).
- Sleep deep metrics / Samsung Health Sensor SDK = future partner track.
- App ≠ watch face packaging; ship both when face changes.
