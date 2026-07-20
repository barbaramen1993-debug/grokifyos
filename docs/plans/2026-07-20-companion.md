# Companion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship the Companion built-in inner app: Live2D avatar stage, SpaceXAI Voice Agent push-to-talk, text chat with TTS replies, custom warm-friend prompt, bundled + optional user Live2D model.

**Architecture:** Host module `companion` registered in `BuiltinPluginCatalog`. Compose shell owns voice/chat/settings; offline WebView under `assets/companion/` runs Pixi + Live2D with a Kotlin JS bridge for state/mouth/model load. Voice uses `GrokAssistantVoiceClient` via a dedicated `CompanionVoiceSession` (do not share the `GrokAssistantVoiceSession` singleton). Text uses `HostAiClient.complete` + `HostAiClient.speak`. Prefs in `CompanionStore`. Shared chat history across voice and text.

**Tech Stack:** Kotlin, Jetpack Compose, WebView, PixiJS + pixi-live2d-display (vendored assets), SharedPreferences, existing `HostAiClient` / vault `spacexai_api_key`, `GROK_VOICES`.

**Design doc:** `docs/plans/2026-07-20-companion-design.md`

---

### Task 1: Amplitude mapping (pure logic + tests)

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/companion/CompanionAmplitude.kt`
- Create: `android/app/src/test/java/io/grokify/os/apps/companion/CompanionAmplitudeTest.kt`

**Step 1: Write the failing test**

```kotlin
package io.grokify.os.apps.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CompanionAmplitudeTest {
    @Test
    fun rms_silence_is_near_zero() {
        val pcm = ShortArray(480) { 0 }
        assertTrue(CompanionAmplitude.rmsPcm16(pcm, pcm.size) < 0.001f)
    }

    @Test
    fun rms_loud_is_higher_than_quiet() {
        val quiet = ShortArray(480) { (it % 2 * 800).toShort() }
        val loud = ShortArray(480) { (it % 2 * 12000).toShort() }
        assertTrue(
            CompanionAmplitude.rmsPcm16(loud, loud.size) >
                CompanionAmplitude.rmsPcm16(quiet, quiet.size),
        )
    }

    @Test
    fun mouth_clamps_and_smooths() {
        val s = CompanionAmplitude.MouthSmoother(attack = 0.6f, release = 0.25f)
        val a = s.next(0f)
        assertEquals(0f, a, 0.001f)
        val b = s.next(1f)
        assertTrue(b in 0.01f..1f)
        val c = s.next(0f)
        assertTrue(c < b)
        assertTrue(s.next(2f) <= 1f)
        assertTrue(s.next(-1f) >= 0f)
    }

    @Test
    fun bytes_to_shorts_little_endian() {
        val bytes = byteArrayOf(0x00, 0x10, 0xFF.toByte(), 0x7F)
        val shorts = CompanionAmplitude.pcm16LeToShorts(bytes)
        assertEquals(2, shorts.size)
        assertEquals(0x1000.toShort(), shorts[0])
        assertEquals(0x7FFF.toShort(), shorts[1])
    }
}
```

**Step 2: Run test to verify it fails**

```bash
cd /root/grokifyos/android && ./gradlew :app:testDebugUnitTest --tests 'io.grokify.os.apps.companion.CompanionAmplitudeTest' 2>&1 | tail -30
```

Expected: FAIL (class not found / unresolved reference).

**Step 3: Minimal implementation**

```kotlin
package io.grokify.os.apps.companion

import kotlin.math.min
import kotlin.math.sqrt

object CompanionAmplitude {
    fun pcm16LeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 2
        val out = ShortArray(n)
        var i = 0
        var j = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            out[j++] = ((hi shl 8) or lo).toShort()
            i += 2
        }
        return out
    }

    /** RMS of PCM16 samples normalized roughly to 0..1 (full-scale ~1). */
    fun rmsPcm16(samples: ShortArray, count: Int): Float {
        val n = min(count, samples.size)
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    fun rmsPcm16Bytes(bytes: ByteArray): Float {
        val s = pcm16LeToShorts(bytes)
        return rmsPcm16(s, s.size)
    }

    /** Mouth open amount 0..1 from RMS with attack/release smoothing. */
    class MouthSmoother(
        private val attack: Float = 0.55f,
        private val release: Float = 0.22f,
        private val gain: Float = 4.5f,
        private val noiseGate: Float = 0.012f,
    ) {
        private var value = 0f

        fun next(rms: Float): Float {
            val target = when {
                rms < noiseGate -> 0f
                else -> min(1f, (rms - noiseGate) * gain).coerceIn(0f, 1f)
            }
            val coef = if (target > value) attack else release
            value += (target - value) * coef
            return value.coerceIn(0f, 1f)
        }

        fun reset() {
            value = 0f
        }
    }
}
```

**Step 4: Run tests — expect PASS**

```bash
cd /root/grokifyos/android && ./gradlew :app:testDebugUnitTest --tests 'io.grokify.os.apps.companion.CompanionAmplitudeTest' 2>&1 | tail -20
```

**Step 5: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/companion/CompanionAmplitude.kt \
  android/app/src/test/java/io/grokify/os/apps/companion/CompanionAmplitudeTest.kt
git commit -m "feat(companion): add PCM amplitude to mouth mapping"
```

---

### Task 2: Default prompt + history helpers

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/companion/CompanionPrompts.kt`
- Create: `android/app/src/test/java/io/grokify/os/apps/companion/CompanionPromptsTest.kt`

**Step 1: Failing tests**

```kotlin
package io.grokify.os.apps.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPromptsTest {
    @Test
    fun default_prompt_is_non_blank_warm_friend() {
        val p = CompanionPrompts.DEFAULT_SYSTEM
        assertTrue(p.length > 40)
        assertTrue(p.contains("Companion", ignoreCase = true) || p.contains("friend", ignoreCase = true))
    }

    @Test
    fun assemble_uses_custom_when_non_blank() {
        val custom = "You are Nova, a calm guide."
        assertEquals(custom, CompanionPrompts.assembleSystem(custom))
    }

    @Test
    fun assemble_falls_back_to_default() {
        assertEquals(CompanionPrompts.DEFAULT_SYSTEM, CompanionPrompts.assembleSystem("  "))
        assertEquals(CompanionPrompts.DEFAULT_SYSTEM, CompanionPrompts.assembleSystem(null))
    }

    @Test
    fun history_cap_keeps_last_n() {
        val msgs = (1..50).map {
            CompanionMessage(
                id = "m$it",
                role = if (it % 2 == 0) "assistant" else "user",
                text = "t$it",
                ts = it.toLong(),
                source = "text",
            )
        }
        val capped = CompanionPrompts.capHistory(msgs, 40)
        assertEquals(40, capped.size)
        assertEquals("m11", capped.first().id)
        assertEquals("m50", capped.last().id)
    }

    @Test
    fun context_window_prefers_recent_user_assistant() {
        val msgs = listOf(
            CompanionMessage("1", "system", "sys", 1, "text"),
            CompanionMessage("2", "user", "hi", 2, "text"),
            CompanionMessage("3", "assistant", "hello", 3, "voice"),
            CompanionMessage("4", "error", "x", 4, "text"),
            CompanionMessage("5", "user", "bye", 5, "text"),
        )
        val win = CompanionPrompts.contextWindow(msgs, maxMessages = 4)
        assertTrue(win.none { it.role == "system" || it.role == "error" })
        assertEquals("bye", win.last().text)
    }

    @Test
    fun encode_decode_round_trip() {
        val list = listOf(
            CompanionMessage("a", "user", "hello", 1L, "text"),
            CompanionMessage("b", "assistant", "hi", 2L, "voice"),
        )
        val json = CompanionPrompts.encodeHistory(list)
        val back = CompanionPrompts.decodeHistory(json)
        assertEquals(2, back.size)
        assertEquals("hello", back[0].text)
        assertEquals("voice", back[1].source)
    }
}
```

**Step 2: Run — expect FAIL**

```bash
cd /root/grokifyos/android && ./gradlew :app:testDebugUnitTest --tests 'io.grokify.os.apps.companion.CompanionPromptsTest' 2>&1 | tail -20
```

**Step 3: Implement**

```kotlin
package io.grokify.os.apps.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CompanionMessage(
    val id: String,
    val role: String, // user | assistant | system | error
    val text: String,
    val ts: Long,
    val source: String = "text", // voice | text
) {
    companion object {
        fun user(text: String, source: String = "text") =
            CompanionMessage(UUID.randomUUID().toString(), "user", text.trim(), System.currentTimeMillis(), source)

        fun assistant(text: String, source: String = "text") =
            CompanionMessage(UUID.randomUUID().toString(), "assistant", text.trim(), System.currentTimeMillis(), source)

        fun error(text: String) =
            CompanionMessage(UUID.randomUUID().toString(), "error", text.trim(), System.currentTimeMillis(), "text")
    }
}

object CompanionPrompts {
    const val HISTORY_CAP = 40
    const val CONTEXT_MAX_MESSAGES = 24

    val DEFAULT_SYSTEM: String = """
        You are Companion, a warm, supportive friend living as an animated character on the user's phone.
        Be casual, curious, and lightly humorous without being chaotic. Keep replies concise and easy to speak aloud.
        Never claim you ran tools, opened files, or changed device settings unless the host actually did.
        Stay in character as Companion. If unsure, ask a short clarifying question.
    """.trimIndent()

    fun assembleSystem(custom: String?): String {
        val t = custom?.trim().orEmpty()
        return if (t.isEmpty()) DEFAULT_SYSTEM else t
    }

    fun capHistory(messages: List<CompanionMessage>, cap: Int = HISTORY_CAP): List<CompanionMessage> {
        if (messages.size <= cap) return messages
        return messages.takeLast(cap)
    }

    fun contextWindow(
        messages: List<CompanionMessage>,
        maxMessages: Int = CONTEXT_MAX_MESSAGES,
    ): List<CompanionMessage> {
        val filtered = messages.filter { it.role == "user" || it.role == "assistant" }
        return filtered.takeLast(maxMessages)
    }

    /** Flatten recent turns into a short block for voice session.instructions or complete history. */
    fun formatHistoryBlock(messages: List<CompanionMessage>): String {
        val win = contextWindow(messages)
        if (win.isEmpty()) return ""
        return win.joinToString("\n") { m ->
            val who = if (m.role == "user") "User" else "Companion"
            "$who: ${m.text}"
        }
    }

    fun encodeHistory(messages: List<CompanionMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role)
                    .put("text", m.text)
                    .put("ts", m.ts)
                    .put("source", m.source),
            )
        }
        return arr.toString()
    }

    fun decodeHistory(raw: String?): List<CompanionMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                    val role = o.optString("role", "user")
                    val text = o.optString("text", "")
                    val ts = o.optLong("ts", 0L)
                    val source = o.optString("source", "text").ifBlank { "text" }
                    if (text.isNotBlank() || role == "error") {
                        add(CompanionMessage(id, role, text, ts, source))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
```

**Step 4: Run tests — PASS**

**Step 5: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/companion/CompanionPrompts.kt \
  android/app/src/test/java/io/grokify/os/apps/companion/CompanionPromptsTest.kt
git commit -m "feat(companion): default prompt and chat history helpers"
```

---

### Task 3: CompanionStore

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/companion/CompanionStore.kt`

**Step 1: Implement store** (mirror `GrokAssistantStore` style)

```kotlin
package io.grokify.os.apps.companion

import android.content.Context

class CompanionStore(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var systemPrompt: String
        get() = prefs.getString(KEY_PROMPT, null)?.let {
            if (it.isBlank()) CompanionPrompts.DEFAULT_SYSTEM else it
        } ?: CompanionPrompts.DEFAULT_SYSTEM
        set(v) = prefs.edit().putString(KEY_PROMPT, v).apply()

    fun resetSystemPrompt() {
        systemPrompt = CompanionPrompts.DEFAULT_SYSTEM
    }

    var voiceId: String
        get() = prefs.getString(KEY_VOICE, "eve")?.ifBlank { "eve" } ?: "eve"
        set(v) = prefs.edit().putString(KEY_VOICE, v.ifBlank { "eve" }).apply()

    var preferDeviceTts: Boolean
        get() = prefs.getBoolean(KEY_PREFER_DEVICE, false)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_DEVICE, v).apply()

    /** bundled | user */
    var modelSource: String
        get() = prefs.getString(KEY_MODEL_SOURCE, SOURCE_BUNDLED)?.ifBlank { SOURCE_BUNDLED } ?: SOURCE_BUNDLED
        set(v) = prefs.edit().putString(
            KEY_MODEL_SOURCE,
            if (v == SOURCE_USER) SOURCE_USER else SOURCE_BUNDLED,
        ).apply()

    var userModelPath: String
        get() = prefs.getString(KEY_USER_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_USER_MODEL, v).apply()

    fun history(): List<CompanionMessage> =
        CompanionPrompts.decodeHistory(prefs.getString(KEY_HISTORY, null))

    fun saveHistory(messages: List<CompanionMessage>) {
        val capped = CompanionPrompts.capHistory(messages)
        prefs.edit().putString(KEY_HISTORY, CompanionPrompts.encodeHistory(capped)).apply()
    }

    fun appendMessage(msg: CompanionMessage): List<CompanionMessage> {
        val next = CompanionPrompts.capHistory(history() + msg)
        saveHistory(next)
        return next
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val PREFS = "companion_prefs"
        private const val KEY_PROMPT = "system_prompt"
        private const val KEY_VOICE = "voice_id"
        private const val KEY_PREFER_DEVICE = "prefer_device_tts"
        private const val KEY_MODEL_SOURCE = "model_source"
        private const val KEY_USER_MODEL = "user_model_path"
        private const val KEY_HISTORY = "chat_history_v1"

        const val SOURCE_BUNDLED = "bundled"
        const val SOURCE_USER = "user"
    }
}
```

**Step 2: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/companion/CompanionStore.kt
git commit -m "feat(companion): SharedPreferences store for prompt voice model history"
```

---

### Task 4: Catalog entry + Apps hub routing

**Files:**
- Modify: `android/app/src/main/java/io/grokify/os/apps/plugin/BuiltinPluginCatalog.kt`
- Modify: `android/app/src/main/java/io/grokify/os/ui/GrokifyAppRoot.kt` (host module `when` + optional short label)
- Create: `android/app/src/main/java/io/grokify/os/apps/companion/CompanionApp.kt` (stub pane first)

**Step 1: Add catalog constant + manifest**

In `BuiltinPluginCatalog`:

```kotlin
const val COMPANION = "companion"
```

Add to `all` list (after Grok Assistant is fine):

```kotlin
PluginManifest(
    id = COMPANION,
    title = "Companion",
    subtitle = "Live2D avatar you can talk to — SpaceXAI voice, chat, custom personality.",
    version = "1.0.0",
    source = PluginSource.Builtin,
    kind = PluginKind.HostModule,
    hostModuleId = COMPANION,
    capabilities = listOf("AI", "Voice", "Chat", "Avatar"),
    accent = PluginAccent.Rose,
    icon = PluginIconKey.Apps, // or Extension if preferred
    featured = true,
    requiredKeys = listOf(
        PluginRequiredKey(
            id = "spacexai_api_key",
            label = "SpaceXAI API key",
            description = "Voice Agent + TTS. Device TTS can cover text-path speak without it.",
            required = false,
        ),
    ),
),
```

**Step 2: Stub pane**

```kotlin
package io.grokify.os.apps.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CompanionPane(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Companion", style = MaterialTheme.typography.headlineSmall)
        Text("Avatar stage coming online…", Modifier.padding(top = 8.dp))
    }
}
```

**Step 3: Route in `GrokifyAppRoot`**

Find the host-module `when` near `GROK_ASSISTANT` and add:

```kotlin
BuiltinPluginCatalog.COMPANION, "companion" -> CompanionPane(
    onBack = onBackToHub,
)
```

Import `io.grokify.os.apps.companion.CompanionPane`.

If there is a short-label map (`GROK_ASSISTANT -> "Assistant"`), add `COMPANION -> "Companion"`.

**Step 4: Compile check**

```bash
cd /root/grokifyos/android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -40
```

Expected: SUCCESS (or only pre-existing unrelated errors).

**Step 5: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/plugin/BuiltinPluginCatalog.kt \
  android/app/src/main/java/io/grokify/os/ui/GrokifyAppRoot.kt \
  android/app/src/main/java/io/grokify/os/apps/companion/CompanionApp.kt
git commit -m "feat(companion): register Apps hub host module and stub pane"
```

---

### Task 5: Live2D stage assets (HTML/JS shell + bundled model)

**Files:**
- Create: `android/app/src/main/assets/companion/index.html`
- Create: `android/app/src/main/assets/companion/js/companion-stage.js`
- Create: `android/app/src/main/assets/companion/css/stage.css`
- Create: `android/app/src/main/assets/companion/models/default/` (model pack)
- Create: `android/app/src/main/assets/companion/README.md` (license notes)
- Vendor minified libs under `assets/companion/js/vendor/` (no runtime CDN)

**Step 1: Vendor JS offline**

Download into `assets/companion/js/vendor/` (pin versions; do not load from network at runtime):

- PixiJS v7 build suitable for `pixi-live2d-display`
- `pixi-live2d-display` (cubism4) + Cubism core if required by the chosen version

Document exact URLs/versions in `assets/companion/README.md`.

**Step 2: Default model**

Ship a **redistributable** Live2D Cubism 4 sample under `models/default/` (e.g. official free sample such as **Hiyori** / project-approved alternative). Include LICENSE file. Structure:

```
models/default/
  <name>.model3.json
  ... textures, motions, expressions ...
  LICENSE
```

If a full model cannot be vendored in-tree yet, still ship the stage that loads `models/default/*.model3.json` and a README “drop model here”; **blocker for success criteria is a working bundled model** — prefer vendoring.

**Step 3: Stage bridge API (JS)**

`companion-stage.js` must expose:

```js
window.CompanionStage = {
  loadModel: async function (source, path) { /* bundled | user */ },
  setState: function (state) { /* idle|listening|thinking|speaking */ },
  setMouth: function (v) { /* 0..1 */ },
  playMotion: function (name) {},
};
```

On boot: notify host `GrokifyCompanion.onReady()`.  
On model success/fail: `onModelLoaded` / `onError`.  
On canvas tap: `onAvatarTapped()`.

Map states to idle motion / expression; drive ParamMouthOpenY (or equivalent) from `setMouth`.

**Step 4: Minimal `index.html`**

Full-screen transparent/dark canvas, loads vendor + stage JS, starts with bundled model.

**Step 5: Commit assets**

```bash
git add android/app/src/main/assets/companion
git commit -m "feat(companion): offline Live2D WebView stage assets and default model"
```

---

### Task 6: Compose Live2D WebView bridge

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/companion/CompanionLive2dStage.kt`

**Step 1: Implement Compose `AndroidView` WebView** (pattern from `WifiMapView.kt`)

Requirements:

- Load `file:///android_asset/companion/index.html`
- `JavaScriptEnabled = true`; allow file access for assets; block cleartext remote where practical
- `@JavascriptInterface` name `GrokifyCompanion` with:
  - `onReady()`
  - `onModelLoaded(String detail)`
  - `onError(String message)`
  - `onAvatarTapped()`
- Host → JS via `evaluateJavascript`:
  - `window.CompanionStage.setState(...)`
  - `window.CompanionStage.setMouth(...)`
  - `window.CompanionStage.loadModel(...)`
- Public Compose API:

```kotlin
@Composable
fun CompanionLive2dStage(
    modelSource: String,
    userModelPath: String,
    avatarState: CompanionAvatarState,
    mouth: Float,
    onReady: () -> Unit = {},
    onModelError: (String) -> Unit = {},
    onAvatarTapped: () -> Unit = {},
    modifier: Modifier = Modifier,
)

enum class CompanionAvatarState { Idle, Listening, Thinking, Speaking }
```

- Throttle mouth JS updates (e.g. only if delta > 0.03 or every 33ms)
- `DisposableEffect` destroys WebView cleanly
- On model error from user pack, callback so UI can set store to bundled and reload

**Step 2: Compile**

```bash
cd /root/grokifyos/android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -30
```

**Step 3: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/companion/CompanionLive2dStage.kt
git commit -m "feat(companion): WebView Live2D stage with JS bridge"
```

---

### Task 7: CompanionVoiceSession (PTT + amplitude)

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/companion/CompanionVoiceSession.kt`

**Design notes:**  
Do **not** use `GrokAssistantVoiceSession` singleton (conflicts if both panes exist). Build a focused session:

- `GrokAssistantVoiceClient` for WebSocket
- `AudioRecord` 24 kHz mono PCM16 (match `GrokAssistantVoiceClient.SAMPLE_RATE`)
- `AudioTrack` for output PCM
- Base64 append path (default JSON transport) unless binary already proven in assistant
- Empty tools array for v1 (`JSONArray()`)
- `sessionUpdate(instructions, voice, tools, reasoningEffort = "none")`
- Vault key: `HostApiKeyStore` / `ApiKeyIds` same as Grok Assistant (`spacexai_api_key`)

**Step 1: API surface**

```kotlin
object CompanionVoiceSession {
    enum class Turn { Idle, Connecting, Listening, Thinking, Speaking, Error }

    data class Snapshot(
        val turn: Turn,
        val statusLine: String?,
        val partialUser: String?,
        val partialAssistant: String?,
        val mouth: Float,
        val level: Float,
    )

    interface Listener {
        fun onSnapshot(snap: Snapshot)
        fun onTranscriptCommitted(role: String, text: String)
        fun onError(message: String)
    }

    fun start(
        ctx: Context,
        instructions: String,
        voiceId: String,
        listener: Listener,
    )
    fun stop()
    fun interrupt() // cancel response + clear playback
    fun isActive(): Boolean
}
```

**Step 2: Wire events** (minimal set)

- Connect → `session.update` with Companion instructions (+ optional short history block prepended)
- Mic thread while Listening: send `input_audio_buffer.append`
- On assistant audio deltas: write AudioTrack; update mouth via `CompanionAmplitude`
- Map server events to Turn: speech_started → Listening; response created → Thinking; audio delta → Speaking; response.done → Listening
- `onTranscriptCommitted` for user/assistant finals → UI appends to `CompanionStore`

Keep v1 simpler than Grok Assistant: no tool loop, no wake word, shorter stall handling is OK but must not hang forever (reuse timeouts ~35s thinking cancel if stuck).

**Step 3: Compile + unit-test pure helpers if any extracted**

**Step 4: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/companion/CompanionVoiceSession.kt
git commit -m "feat(companion): Voice Agent session with lip-sync amplitude"
```

---

### Task 8: Full Companion UI (stage + PTT + chat + settings)

**Files:**
- Modify: `android/app/src/main/java/io/grokify/os/apps/companion/CompanionApp.kt` (replace stub)

**Step 1: Layout**

```
Scaffold / Column
├── TopBar: Back | Companion | connection chip | Settings icon
├── CompanionLive2dStage (weight 1f)
├── Dock: Hold-to-talk (pointerInput) | Stop | Chat toggle
└── Optional ModalBottomSheet: Chat transcript + TextField + Send
    Settings sheet: prompt editor, reset, voice chips (GROK_VOICES),
    model source radio, "Load model" (SAF), clear history
```

**Step 2: Text send pipeline**

```kotlin
// on IO dispatcher
val system = CompanionPrompts.assembleSystem(store.systemPrompt)
val historyBlock = CompanionPrompts.formatHistoryBlock(store.history())
val promptForModel = if (historyBlock.isBlank()) userText
    else "Recent conversation:\n$historyBlock\n\nUser: $userText"
val options = JSONObject()
    .put("system", system)
    .put("session_title", "Companion")
    .toString()
val raw = HostAiClient.complete(ctx, promptForModel, options)
// parse ok/text; append messages; HostAiClient.speak with voice_id + prefer_device
// set avatarState Thinking → Speaking (mouth from speak is coarse: pulse or
// approximate with timed setMouth if PCM not available from speak API)
```

For text-path TTS: if `HostAiClient.speak` does not expose PCM, drive avatar `Speaking` state for speak duration and gentle fake mouth oscillation OR silence mouth (state alone). Prefer real amplitude only on Voice Agent path in v1.

**Step 3: PTT**

- `awaitPointerEventScope` / `detectTapGestures(onPress=)` on mic button and optionally avatar tap from bridge
- On press: `CompanionVoiceSession.start(...)` if not active; ensure RECORD_AUDIO
- On release: do **not** necessarily stop session if using server VAD continuous mode — design is hold-to-talk: either  
  - **A)** session stays Live and release ends user turn via `input_audio_buffer.commit` if API supports, or  
  - **B)** start on press, stop after response.done  
- Prefer **simpler B for v1**: press starts (or unmutes mic), release mutes mic / commits; session can stay open for multi-turn until Stop. If commit API is unclear, match Grok Assistant hold behavior from `GrokAssistant.kt` PTT if present.

**Step 4: Permissions**

Request `RECORD_AUDIO` when user first holds mic; surface rationale.

**Step 5: Model load UI**

- Bundled: `modelSource = bundled`, `loadModel("bundled")`
- User: SAF open document tree or single `.model3.json`; copy into `filesDir/companion/user_model/`; set path; `loadModel("user", path)`
- On error: toast + force bundled

**Step 6: Compile**

```bash
cd /root/grokifyos/android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -40
```

**Step 7: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/companion/
git commit -m "feat(companion): main UI with Live2D, PTT, chat, and settings"
```

---

### Task 9: Wire mouth/state from voice session into stage

**Files:**
- Modify: `CompanionApp.kt`, possibly small hooks in `CompanionLive2dStage.kt`

**Step 1:** Collect `CompanionVoiceSession` snapshots on main thread →  
`avatarState` from `Turn` mapping:  
`Idle→Idle, Connecting/Listening→Listening, Thinking→Thinking, Speaking→Speaking, Error→Idle`  
`mouth` from snapshot.

**Step 2:** On transcript commits, append to store and refresh chat list.

**Step 3:** Manual sanity (if emulator/device available): open Companion → model loads → hold mic → reply + mouth moves.

**Step 4: Commit**

```bash
git commit -am "feat(companion): drive Live2D state and mouth from voice session"
```

---

### Task 10: Unit tests for store edge cases (optional but preferred)

**Files:**
- Create: `android/app/src/test/java/io/grokify/os/apps/companion/CompanionStoreTest.kt`  
  Use Robolectric if already on classpath; else keep pure tests only on `CompanionPrompts` / amplitude (already done).

If Robolectric not available, skip store instrumented tests — rely on Task 2 encode/decode.

**Commit if added.**

---

### Task 11: Version bump + OTA publish

**Files:**
- Modify: `android/app/build.gradle.kts` (`versionCode` / `versionName`)

**Step 1:** Bump e.g. `190` → `191`, `0.1.190` → `0.1.191` (use current values +1 at ship time).

**Step 2:** Publish debug OTA per AGENTS.md:

```bash
cd /root/grokifyos/android && ./scripts/publish.sh debug --changelog "Companion: Live2D avatar + voice PTT + chat TTS"
```

**Step 3: Commit version bump** (if publish script does not)

```bash
git add android/app/build.gradle.kts
git commit -m "chore(android): bump version for Companion release"
```

---

### Task 12: Final verification checklist

Run:

```bash
cd /root/grokifyos/android && ./gradlew :app:testDebugUnitTest --tests 'io.grokify.os.apps.companion.*' 2>&1 | tail -40
cd /root/grokifyos/android && ./gradlew :app:assembleDebug 2>&1 | tail -40
```

Manual (device):

1. Apps hub shows **Companion**  
2. Bundled Live2D idles  
3. Hold-to-talk gets spoken reply; mouth moves  
4. Text send shows reply + TTS  
5. Edit system prompt persists  
6. Voice chip changes voice  
7. Bad user model → toast + bundled fallback  
8. Clear history works  

---

## Task dependency graph

```
T1 Amplitude ──┐
T2 Prompts ────┼── T3 Store ── T4 Catalog/stub ── T8 UI ── T9 Wire ── T11 Ship
T5 Assets ─────┼── T6 Stage ──┘         │
T7 Voice ───────────────────────────────┘
T10 tests (parallel after T2/T3)
T12 verify after T11
```

## Risk notes

| Risk | Mitigation |
|---|---|
| Live2D model license | Document in assets README; use free sample only |
| WebView GPU/memory | Single WebView; destroy on leave; static fallback |
| Voice session complexity | No tools/wake; reuse client only; shorter state machine |
| Concurrent Grok Assistant voice | Separate session object; stop on dispose |
| `speak` without PCM | State-only avatar for text TTS in v1 |

## Out of scope (do not implement in this plan)

Wake word, overlay, multi-character gallery, marketplace, native Cubism SDK, viseme ML.
