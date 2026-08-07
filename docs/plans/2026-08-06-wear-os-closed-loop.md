# Wear OS closed loop — Implementation Plan

> **For agents:** Implement task-by-task. Prefer small commits per task. Do not break phone OTA (`channel=phone` default).

**Goal:** Add a **standalone blank Wear AI-assistant shell** + multi-channel publish + phone OTA download → wireless-ADB install to the watch, managed by a **release-build** phone inner app that later also views data the watch sends back.

**Product locks:** Not a copy of the phone app; UI/UX iterated in chat; standalone sideload; deploy in release builds; phone is the watch OTA hop.

**Architecture:** Gradle `:wear` (own app id); channels `phone` | `wear`; phone **Watch Deploy** downloads `channel=wear` and `adb install`s over Wi‑Fi.

**Tech stack:** Kotlin, Wear Compose, existing PHP API, OkHttp download, ProcessBuilder adb, schema migration 003.

**Design doc:** `docs/plans/2026-08-06-wear-os-closed-loop-design.md`

---

### Task 1: Scaffold `:wear` module (blank shell APK)

**Files:**
- Create: `android/wear/build.gradle.kts`
- Create: `android/wear/src/main/AndroidManifest.xml`
- Create: `android/wear/src/main/java/io/grokify/os/wear/MainActivity.kt`
- Create: `android/wear/src/main/res/values/strings.xml` (+ minimal theme/icons as needed)
- Modify: `android/settings.gradle.kts` — `include(":wear")`
- Modify: `android/scripts/build.sh` — support `wear` / `all` targets

**Step 1:** Separate module (not a fork of `:app`). `applicationId = "io.grokify.os.wear"`, `versionCode = 1`, `versionName = "0.1.1"`, minSdk 30, `standalone=true`. Wear Compose **blank/minimal home** — no phone features. Show **version only** so install success is checkable. Product intent comment: AI assistant; UI TBD via chat.

**Step 2:** Build

```bash
cd /root/grokifyos/android && ./scripts/build.sh debug wear
```

Expected: APK at `wear/build/outputs/apk/debug/wear-debug.apk` (path may vary by AGP; document actual path in publish script).

**Step 3:** Commit scaffold only (no server changes yet).

---

### Task 2: Schema + PHP channel support

**Files:**
- Create: `schema/003_apk_channel.sql`
- Modify: `web/includes/system-chat.php` — `gos_latest_apk`, `gos_register_apk_upload`
- Modify: `web/api/update.php`, `web/api/apk-download.php`
- Modify: `web/api/status.php`, `web/api/me.php` (optional dual summary)
- Apply migration via install path or documented SQL

**Step 1:** Migration adds `channel VARCHAR(16) NOT NULL DEFAULT 'phone'`, unique `(channel, version_code)`, fix active index.

**Step 2:** `gos_latest_apk(string $channel = 'phone')` filters `channel` + `is_active`.

**Step 3:** Register deactivates only rows for **that channel**.

**Step 4:** `update.php` / `apk-download.php` read `channel` query param (default `phone`).

**Step 5:** Manual SQL check:

```sql
SELECT channel, version_code, is_active FROM grokify_apk_releases ORDER BY channel, version_code DESC;
```

---

### Task 3: Publish scripts multi-channel

**Files:**
- Modify: `android/scripts/publish-apk.php`
- Modify: `android/scripts/publish.sh`
- Modify: `android/scripts/install-device.sh` (optional `--wear`)

**Step 1:** `--channel=phone|wear`; resolve APK path + gradle version file per channel.

**Step 2:** Filename prefix includes channel; storage path clear in logs.

**Step 3:** Smoke (after Task 1 APK exists):

```bash
cd /root/grokifyos/android
./scripts/publish.sh debug --channel wear --changelog "wear scaffold" --no-build
# if --no-build needs prior assemble; else full publish
```

Expected: DB row `channel=wear`; phone latest unchanged.

**Step 4:** Regression:

```bash
./scripts/publish.sh debug --channel phone --changelog "phone still works"
```

---

### Task 4: Phone download path for wear channel

**Files:**
- Modify: `android/app/src/main/java/io/grokify/os/update/ApkUpdater.kt`
- Modify: `android/app/src/main/java/io/grokify/os/data/GrokifyApi.kt` (if update check is centralized)
- Modify: `android/app/src/main/java/io/grokify/os/ui/GrokifyViewModel.kt` only if needed for shared helpers

**Step 1:** Add channel parameter to update check + download URL (`?channel=wear`).

**Step 2:** Cache file name `grokify-wear-update.apk` vs phone file to avoid clobber.

**Step 3:** Unit/UI not required; manual API test with curl + device token.

---

### Task 5: Watch ADB client on phone

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/watchdeploy/WatchAdbClient.kt`
- Create: `android/app/src/main/java/io/grokify/os/apps/watchdeploy/WatchDeployStore.kt`
- Add: packaged `adb` binary under app assets or jniLibs (document source + license)
- Extract on first use to `context.filesDir/adb/` with execute bit

**Step 1:** API:

```kotlin
class WatchAdbClient(context: Context) {
  suspend fun ensureReady(): Result<Unit>
  suspend fun connect(hostPort: String): Result<String>  // adb connect
  suspend fun devices(): Result<List<String>>
  suspend fun install(serial: String, apk: File): Result<String>
}
```

**Step 2:** All invocations via `ProcessBuilder`, capture stdout/stderr, timeouts.

**Step 3:** No network install yet — local process tests where possible; real device for connect/install.

---

### Task 6: Watch Deploy phone inner app (release builds)

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/watchdeploy/WatchDeployApp.kt`
- Modify: `apps/plugin/BuiltinPluginCatalog.kt`
- Modify: `ui/GrokifyAppRoot.kt` (route)
- Strings / icons as needed

**Step 1:** UI (release-enabled, developer-labeled): target IP:port, Connect, devices status, Check wear OTA, Download, Install, log pane. Plus a **Data** stub (“wear-reported data will appear here”).

**Step 2:** Wire download (Task 4) + adb install (Task 5) — same OTA idea as phone self-update, target channel `wear`, install hop to watch.

**Step 3:** Persist last host + last installed wear versionCode.

**Step 4:** Do **not** gate on `BuildConfig.DEBUG`. Bump **phone** `versionCode` / publish phone OTA so the device gets Watch Deploy.

---

### Task 7: Agent docs + architecture

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md` (short Wear section)
- Modify: `docs/ARCHITECTURE.md`
- Modify: `android/README.md`

**Content:** dual release rules; wear = standalone blank AI-assistant shell (iterate in chat, not a phone clone); Watch Deploy in release; phone OTA-hops wear APK to watch; wireless ADB prerequisites on Galaxy Watch; data-view half of inner app is stub until wear reports payloads.

---

### Task 8: E2E verification on hardware

**Manual:**

1. Watch: developer options → ADB + Debug over Wi‑Fi  
2. Publish wear change (visible version string)  
3. Phone Watch Deploy → connect → install  
4. Confirm version on watch  
5. Publish phone-only change → confirm wear latest unchanged and phone OTA still works  

**Record:** IPs, any RSA prompt notes, binary path that worked.

---

### Task 9 (optional v1.1): Pending wear deploy prompt

**Files:** server flag or compare `me`/status wear latest vs `WatchDeployStore`; notification when phone sees newer wear channel.

Skip until Task 8 is green.

---

## Dependency order

```text
Task 1 → Task 2 → Task 3
                ↘ Task 4 → Task 5 → Task 6 → Task 7 → Task 8
```

Tasks 2–3 can proceed once Task 1 APK path is known; Task 5 can prototype with a host-built wear APK copied via `adb push` before Task 4.

## Risk controls

- Default `channel=phone` everywhere → zero behavior change for existing clients  
- Never `UPDATE … is_active=0` without `AND channel = ?`  
- Independent versionCode streams  
- Label Watch Deploy as developer tooling  
- If bundled adb is blocked on a device build, document host `install-device.sh --wear` fallback  

## Out of scope reminders

Samsung Health Sensor SDK, Play distribution, finished on-watch AI UX in scaffold, cloning phone host into wear, companion-only packaging, full wear→phone telemetry before deploy loop is green.
