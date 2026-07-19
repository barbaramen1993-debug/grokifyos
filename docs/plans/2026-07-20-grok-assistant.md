# Grok Assistant Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship the Grok Assistant built-in inner app (Chat + Setup): master toggle, Conversation/Dev modes, editable/resettable prompt templates, Grok Build chat via `HostAiClient.complete`, and Live DJ–shared TTS via `HostAiClient.speak` + vault `spacexai_api_key`.

**Architecture:** New host module (`grok_assistant`) registered in `BuiltinPluginCatalog`, Compose pane with Chat|Setup tabs, `GrokAssistantStore` (SharedPreferences), `GrokAssistantPrompts` (defaults + JSON), send pipeline on IO with transcript cap. No wake word, overlay, or system assistant in this plan.

**Tech Stack:** Kotlin, Jetpack Compose, SharedPreferences, existing `HostAiClient`, `GROK_VOICES`, Grokify API bridge, Android host APK.

**Design doc:** `docs/plans/2026-07-20-grok-assistant-design.md`

---

### Task 1: Prompt model + system string assembly (pure logic)

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/GrokAssistantPrompts.kt`
- Create: `android/app/src/test/java/io/grokify/os/apps/GrokAssistantPromptsTest.kt` (if `src/test` exists or create it)

**Step 1: Create pure prompt types and defaults**

Implement in `GrokAssistantPrompts.kt`:

```kotlin
package io.grokify.os.apps

import org.json.JSONArray
import org.json.JSONObject

enum class AssistantPromptKind {
    Core, Mode, Extra;

    val storageKey: String
        get() = when (this) {
            Core -> "core"
            Mode -> "mode"
            Extra -> "extra"
        }

    val sectionLabel: String
        get() = when (this) {
            Core -> "Core identity"
            Mode -> "Mode prompts"
            Extra -> "Style extras"
        }

    companion object {
        fun fromStorage(raw: String?): AssistantPromptKind? =
            when (raw?.lowercase()?.trim()) {
                "core" -> Core
                "mode" -> Mode
                "extra", "style" -> Extra
                else -> null
            }
    }
}

enum class AssistantMode {
    Conversation, Dev;

    val storageKey: String
        get() = when (this) {
            Conversation -> "conversation"
            Dev -> "dev"
        }

    val modePromptId: String
        get() = when (this) {
            Conversation -> AssistantPromptDefaults.ID_MODE_CONVERSATION
            Dev -> AssistantPromptDefaults.ID_MODE_DEV
        }

    companion object {
        fun fromStorage(raw: String?): AssistantMode =
            when (raw?.lowercase()?.trim()) {
                "dev", "developer" -> Dev
                else -> Conversation
            }
    }
}

data class AssistantPromptTemplate(
    val id: String,
    val kind: AssistantPromptKind,
    val label: String,
    val blurb: String = "",
    val body: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind.storageKey)
            .put("label", label)
            .put("blurb", blurb)
            .put("body", body)
            .put("enabled", enabled)
            .put("builtIn", builtIn)

    companion object {
        fun fromJson(o: JSONObject?): AssistantPromptTemplate? {
            if (o == null) return null
            val id = o.optString("id", "").trim()
            val kind = AssistantPromptKind.fromStorage(o.optString("kind", "")) ?: return null
            if (id.isBlank()) return null
            return AssistantPromptTemplate(
                id = id,
                kind = kind,
                label = o.optString("label", id).ifBlank { id },
                blurb = o.optString("blurb", ""),
                body = o.optString("body", ""),
                enabled = o.optBoolean("enabled", true),
                builtIn = o.optBoolean("builtIn", false),
            )
        }
    }
}

object AssistantPromptDefaults {
    const val ID_CORE = "core_identity"
    const val ID_MODE_CONVERSATION = "mode_conversation"
    const val ID_MODE_DEV = "mode_dev"
    const val ID_STYLE_CONCISE = "style_concise"
    const val ID_STYLE_WITTY = "style_witty"
    const val ID_STYLE_SPOKEN = "style_spoken"

    fun all(): List<AssistantPromptTemplate> = listOf(
        AssistantPromptTemplate(
            id = ID_CORE,
            kind = AssistantPromptKind.Core,
            label = "Core identity",
            blurb = "Who the assistant is and hard rules",
            body =
                "You are Grok Assistant, the on-device voice and chat helper for GrokifyOS. " +
                    "Be clear, helpful, and concise. Never invent that you edited files, ran tools, " +
                    "or changed device settings unless the host actually did so. Stay in character. " +
                    "If something needs a host capability that is not wired yet, say so plainly.",
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_MODE_CONVERSATION,
            kind = AssistantPromptKind.Mode,
            label = "Conversation mode",
            blurb = "Everyday Q&A",
            body =
                "MODE: Conversation. Everyday Q&A. Warm and direct. " +
                    "Ask a short clarifying question when the request is ambiguous. " +
                    "Prefer plain language over jargon unless the user is technical.",
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_MODE_DEV,
            kind = AssistantPromptKind.Mode,
            label = "Dev mode",
            blurb = "Engineering partner (text-only tools in v1)",
            body =
                "MODE: Dev. Act as an engineering partner for code, debugging, and architecture. " +
                    "You may reason about files, tools, and patches, but you cannot execute host tools " +
                    "or edit the device filesystem in this mode yet — say when an action needs wiring. " +
                    "Prefer concrete steps, commands, and diffs when useful.",
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_STYLE_CONCISE,
            kind = AssistantPromptKind.Extra,
            label = "Concise",
            blurb = "Prefer short answers",
            body = "STYLE: Keep answers short. Lead with the answer, then brief detail only if needed.",
            enabled = true,
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_STYLE_WITTY,
            kind = AssistantPromptKind.Extra,
            label = "Witty",
            blurb = "Light humor when natural",
            body = "STYLE: Light humor is welcome when it fits; never force jokes over clarity.",
            enabled = false,
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_STYLE_SPOKEN,
            kind = AssistantPromptKind.Extra,
            label = "Spoken-friendly",
            blurb = "Optimize for TTS",
            body =
                "STYLE: Optimize for speech. Short sentences. No markdown tables. " +
                    "Avoid long code fences unless the user asked for code.",
            enabled = true,
            builtIn = true,
        ),
    )

    fun byId(id: String): AssistantPromptTemplate? = all().find { it.id == id }
}

object AssistantPromptCodec {
    fun encode(list: List<AssistantPromptTemplate>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decode(raw: String?): List<AssistantPromptTemplate> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AssistantPromptTemplate>()
        for (i in 0 until arr.length()) {
            AssistantPromptTemplate.fromJson(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    /** Merge saved with defaults: keep user edits; ensure built-ins exist. */
    fun mergeWithDefaults(saved: List<AssistantPromptTemplate>): List<AssistantPromptTemplate> {
        val defaults = AssistantPromptDefaults.all()
        val byId = saved.associateBy { it.id }.toMutableMap()
        for (d in defaults) {
            if (d.id !in byId) byId[d.id] = d
        }
        // Preserve order: defaults first by default order, then custom extras
        val ordered = mutableListOf<AssistantPromptTemplate>()
        val seen = mutableSetOf<String>()
        for (d in defaults) {
            byId[d.id]?.let {
                ordered.add(it)
                seen.add(it.id)
            }
        }
        for (s in saved) {
            if (s.id !in seen) {
                ordered.add(s)
                seen.add(s.id)
            }
        }
        return ordered
    }

    fun resetTemplate(
        list: List<AssistantPromptTemplate>,
        id: String,
    ): List<AssistantPromptTemplate>? {
        val stock = AssistantPromptDefaults.byId(id) ?: return null
        return list.map { if (it.id == id) stock.copy(enabled = it.enabled) else it }
    }
}

object AssistantSystemPrompt {
    const val SPEAK_HINT =
        "Reply in plain speech-friendly prose; avoid code fences unless the user asked for code."

    fun build(
        templates: List<AssistantPromptTemplate>,
        mode: AssistantMode,
        speakReplies: Boolean,
    ): String {
        val core = templates.firstOrNull {
            it.kind == AssistantPromptKind.Core && it.id == AssistantPromptDefaults.ID_CORE
        }?.body?.trim().orEmpty()
            .ifBlank {
                AssistantPromptDefaults.byId(AssistantPromptDefaults.ID_CORE)?.body.orEmpty()
            }

        val modeBody = templates.firstOrNull { it.id == mode.modePromptId }?.body?.trim()
            .orEmpty()
            .ifBlank {
                AssistantPromptDefaults.byId(mode.modePromptId)?.body.orEmpty()
            }

        val extras = templates
            .filter { it.kind == AssistantPromptKind.Extra && it.enabled && it.body.isNotBlank() }
            .map { it.body.trim() }

        val parts = mutableListOf<String>()
        if (core.isNotBlank()) parts += core
        if (modeBody.isNotBlank()) parts += modeBody
        extras.forEach { parts += it }

        val meta =
            "Mode: ${mode.storageKey} · Speak replies: ${if (speakReplies) "on" else "off"}"
        parts += meta

        val spokenExtraOn = templates.any {
            it.id == AssistantPromptDefaults.ID_STYLE_SPOKEN && it.enabled
        }
        if (speakReplies && !spokenExtraOn) {
            parts += SPEAK_HINT
        }

        return parts.joinToString("\n---\n")
    }
}

data class AssistantChatMessage(
    val id: String,
    val role: String, // user | assistant | system | error
    val text: String,
    val ts: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("role", role).put("text", text).put("ts", ts)

    companion object {
        fun fromJson(o: JSONObject?): AssistantChatMessage? {
            if (o == null) return null
            val id = o.optString("id", "").ifBlank { return null }
            val role = o.optString("role", "").ifBlank { return null }
            val text = o.optString("text", "")
            return AssistantChatMessage(id, role, text, o.optLong("ts", 0L))
        }
    }
}

object AssistantTranscript {
    const val MAX_STORED = 100
    const val MAX_HISTORY_MESSAGES = 24 // 12 turns
    const val MAX_HISTORY_CHARS = 6000

    fun encode(list: List<AssistantChatMessage>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decode(raw: String?): List<AssistantChatMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AssistantChatMessage>()
        for (i in 0 until arr.length()) {
            AssistantChatMessage.fromJson(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun capStored(list: List<AssistantChatMessage>): List<AssistantChatMessage> =
        if (list.size <= MAX_STORED) list else list.takeLast(MAX_STORED)

    /** Last N user/assistant messages for model context (not error/system). */
    fun historyWindow(list: List<AssistantChatMessage>): List<AssistantChatMessage> {
        val filtered = list.filter { it.role == "user" || it.role == "assistant" }
        val tail = filtered.takeLast(MAX_HISTORY_MESSAGES)
        var chars = 0
        val out = ArrayDeque<AssistantChatMessage>()
        for (m in tail.asReversed()) {
            val len = m.text.length
            if (out.isNotEmpty() && chars + len > MAX_HISTORY_CHARS) break
            out.addFirst(m)
            chars += len
        }
        return out.toList()
    }

    fun formatHistoryForPrompt(window: List<AssistantChatMessage>): String {
        if (window.isEmpty()) return ""
        return window.joinToString("\n") { m ->
            val who = if (m.role == "user") "User" else "Assistant"
            "$who: ${m.text}"
        }
    }
}
```

**Step 2: Add unit tests** (create `android/app/src/test/java/io/grokify/os/apps/GrokAssistantPromptsTest.kt`)

If the project has no test source set yet, add to `android/app/build.gradle.kts` under `dependencies`:

```kotlin
testImplementation("junit:junit:4.13.2")
```

Test cases:

```kotlin
package io.grokify.os.apps

import org.junit.Assert.*
import org.junit.Test

class GrokAssistantPromptsTest {
    @Test
    fun build_includesCoreModeAndMeta() {
        val sys = AssistantSystemPrompt.build(
            AssistantPromptDefaults.all(),
            AssistantMode.Dev,
            speakReplies = true,
        )
        assertTrue(sys.contains("Grok Assistant"))
        assertTrue(sys.contains("MODE: Dev") || sys.contains("engineering partner"))
        assertTrue(sys.contains("Mode: dev"))
        assertTrue(sys.contains("Speak replies: on"))
    }

    @Test
    fun mergeWithDefaults_restoresMissingBuiltIns() {
        val merged = AssistantPromptCodec.mergeWithDefaults(emptyList())
        assertEquals(AssistantPromptDefaults.all().size, merged.size)
    }

    @Test
    fun resetTemplate_restoresStockBody() {
        val edited = AssistantPromptDefaults.all().map {
            if (it.id == AssistantPromptDefaults.ID_CORE) it.copy(body = "HACKED") else it
        }
        val reset = AssistantPromptCodec.resetTemplate(edited, AssistantPromptDefaults.ID_CORE)!!
        assertEquals(
            AssistantPromptDefaults.byId(AssistantPromptDefaults.ID_CORE)!!.body,
            reset.first { it.id == AssistantPromptDefaults.ID_CORE }.body,
        )
    }

    @Test
    fun transcript_capAndHistoryWindow() {
        val msgs = (1..120).map {
            AssistantChatMessage(id = "$it", role = if (it % 2 == 0) "assistant" else "user", text = "m$it")
        }
        val capped = AssistantTranscript.capStored(msgs)
        assertEquals(100, capped.size)
        val window = AssistantTranscript.historyWindow(capped)
        assertTrue(window.size <= 24)
    }
}
```

**Step 3: Run tests**

```bash
cd /root/grokifyos/android && ./gradlew :app:testDebugUnitTest --tests 'io.grokify.os.apps.GrokAssistantPromptsTest' 2>&1 | tail -40
```

Expected: PASS (or configure test source set if missing, then PASS).

**Step 4: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/GrokAssistantPrompts.kt \
  android/app/src/test/java/io/grokify/os/apps/GrokAssistantPromptsTest.kt \
  android/app/build.gradle.kts
git commit -m "feat(assistant): prompt defaults, codec, system string assembly"
```

---

### Task 2: GrokAssistantStore

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/GrokAssistantStore.kt`

**Step 1: Implement store**

```kotlin
package io.grokify.os.apps

import android.content.Context
import java.util.UUID

class GrokAssistantStore(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var mode: AssistantMode
        get() = AssistantMode.fromStorage(prefs.getString(KEY_MODE, null))
        set(v) = prefs.edit().putString(KEY_MODE, v.storageKey).apply()

    var voiceId: String
        get() = prefs.getString(KEY_VOICE, "eve")?.ifBlank { "eve" } ?: "eve"
        set(v) = prefs.edit().putString(KEY_VOICE, v.ifBlank { "eve" }).apply()

    var preferDeviceTts: Boolean
        get() = prefs.getBoolean(KEY_PREFER_DEVICE, false)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_DEVICE, v).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(v) = prefs.edit().putBoolean(KEY_SPEAK, v).apply()

    fun templates(): List<AssistantPromptTemplate> {
        val saved = AssistantPromptCodec.decode(prefs.getString(KEY_PROMPTS, null))
        return AssistantPromptCodec.mergeWithDefaults(saved)
    }

    fun saveTemplates(list: List<AssistantPromptTemplate>) {
        prefs.edit().putString(KEY_PROMPTS, AssistantPromptCodec.encode(list)).apply()
    }

    fun upsertTemplate(tpl: AssistantPromptTemplate) {
        val cur = templates().toMutableList()
        val idx = cur.indexOfFirst { it.id == tpl.id }
        if (idx >= 0) cur[idx] = tpl else cur.add(tpl)
        saveTemplates(cur)
    }

    fun setTemplateEnabled(id: String, enabled: Boolean) {
        saveTemplates(templates().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun resetTemplate(id: String): Boolean {
        val next = AssistantPromptCodec.resetTemplate(templates(), id) ?: return false
        saveTemplates(next)
        return true
    }

    fun transcript(): List<AssistantChatMessage> =
        AssistantTranscript.decode(prefs.getString(KEY_TRANSCRIPT, null))

    fun saveTranscript(list: List<AssistantChatMessage>) {
        val capped = AssistantTranscript.capStored(list)
        prefs.edit().putString(KEY_TRANSCRIPT, AssistantTranscript.encode(capped)).apply()
    }

    fun appendMessage(role: String, text: String): AssistantChatMessage {
        val msg = AssistantChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            text = text,
        )
        saveTranscript(transcript() + msg)
        return msg
    }

    fun clearTranscript() {
        prefs.edit().remove(KEY_TRANSCRIPT).apply()
    }

    fun systemPrompt(): String =
        AssistantSystemPrompt.build(templates(), mode, speakReplies)

    companion object {
        private const val PREFS = "grok_assistant_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_VOICE = "voice_id"
        private const val KEY_PREFER_DEVICE = "prefer_device_tts"
        private const val KEY_SPEAK = "speak_replies"
        private const val KEY_PROMPTS = "prompt_templates_v1"
        private const val KEY_TRANSCRIPT = "transcript_v1"
    }
}
```

**Step 2: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/GrokAssistantStore.kt
git commit -m "feat(assistant): SharedPreferences store for prefs and transcript"
```

---

### Task 3: Register catalog entry + route

**Files:**
- Modify: `android/app/src/main/java/io/grokify/os/apps/plugin/BuiltinPluginCatalog.kt`
- Modify: `android/app/src/main/java/io/grokify/os/ui/GrokifyAppRoot.kt` (`AppsPane` when branch ~4341–4360)
- Optionally: `PluginModels.kt` / `RemotePluginCatalog.kt` if adding icon

**Step 1: Catalog**

Add:

```kotlin
const val GROK_ASSISTANT = "grok_assistant"
```

And a `PluginManifest` in `all`:

- id/title: Grok Assistant  
- subtitle: On-device chat + voice assistant. Conversation or Dev mode, editable prompts, Grok Build + TTS.  
- version: `1.0.0`  
- HostModule, accent Violet, icon `PluginIconKey.Apps` (or Extension)  
- capabilities: `listOf("AI", "Voice", "Chat")`  
- requiredKeys: optional `spacexai_api_key` (same description as Spotify TTS)

**Step 2: Route in AppsPane**

```kotlin
BuiltinPluginCatalog.GROK_ASSISTANT, "grok_assistant" -> GrokAssistantPane(
    onBack = onBackToHub,
)
```

(Placeholder pane OK until Task 4 if needed — prefer full pane in Task 4 in same commit batch.)

**Step 3: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/plugin/BuiltinPluginCatalog.kt \
  android/app/src/main/java/io/grokify/os/ui/GrokifyAppRoot.kt
git commit -m "feat(assistant): register Grok Assistant host module in Apps hub"
```

---

### Task 4: Compose pane — shell + Setup tab

**Files:**
- Create: `android/app/src/main/java/io/grokify/os/apps/GrokAssistant.kt`

**Step 1: Scaffold**

- `@Composable fun GrokAssistantPane(onBack: () -> Unit)`
- Top bar with back, title “Grok Assistant”
- Segmented Chat | Setup (remember selected tab)
- `val store = remember { GrokAssistantStore(context) }`
- Local state mirrored from store with `mutableStateOf` + reload helpers

**Step 2: Setup UI**

Mirror Live DJ voice row patterns from `SpotifyController.kt` (~voice chips using `GROK_VOICES`):

1. Master switch → `store.enabled`  
2. Mode segmented → Conversation | Dev  
3. Voice chips (`GROK_VOICES`), Prefer device TTS, Speak replies  
4. Preview button → `HostAiClient.speak` with short fixed line + voice options JSON  
5. Prompts list by kind; tap → editor dialog/sheet (label, blurb, body); Reset for built-ins; toggle enabled for Extra; Add custom Extra  
6. Coming soon disabled rows  

Vault status: use `HostApiKeyStore.getValue(ctx, ApiKeyIds.SPACEXAI)` or equivalent — non-blank = “TTS key present”.

**Step 3: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/GrokAssistant.kt \
  android/app/src/main/java/io/grokify/os/ui/GrokifyAppRoot.kt
git commit -m "feat(assistant): Setup tab — master, mode, voice, prompts"
```

---

### Task 5: Chat tab + send pipeline + TTS

**Files:**
- Modify: `android/app/src/main/java/io/grokify/os/apps/GrokAssistant.kt`

**Step 1: Chat UI**

- LazyColumn of messages (user / assistant / error styling)
- Composer TextField + Send
- Speak replies switch
- Clear with confirm dialog
- Empty state when no messages; if `!enabled` show enable hint

**Step 2: Send pipeline**

```kotlin
// Pseudo — run on Dispatchers.IO inside rememberCoroutineScope
fun send(text: String) {
    if (!store.enabled || busy) return
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    busy = true
    store.appendMessage("user", trimmed)
    reloadTranscript()
    val system = store.systemPrompt()
    val history = AssistantTranscript.formatHistoryForPrompt(
        AssistantTranscript.historyWindow(store.transcript().dropLast(1) /* exclude just-added? include all prior */)
    )
    // Prefer: prior window + current user as `prompt` body for complete()
    val promptBody = buildString {
        if (history.isNotBlank()) {
            append("Recent conversation:\n")
            append(history)
            append("\n\n")
        }
        append(trimmed)
    }
    val options = JSONObject()
        .put("system", system)
        .put("session_title", "Grok Assistant")
        .toString()
    val raw = HostAiClient.complete(appCtx, promptBody, options)
    val json = JSONObject(raw)
    if (json.optBoolean("ok", false)) {
        val reply = json.optString("text", json.optString("content", "")).trim()
        // Parse actual HostAiClient success shape — inspect HostAiClient.complete return keys!
        if (reply.isBlank()) {
            store.appendMessage("error", "Empty reply — try again")
        } else {
            store.appendMessage("assistant", reply)
            if (store.speakReplies) {
                val speakOpts = JSONObject()
                    .put("voice_id", store.voiceId)
                    .put("prefer_device", store.preferDeviceTts)
                    .put("language", "en")
                    .toString()
                HostAiClient.speak(appCtx, reply, speakOpts)
            }
        }
    } else {
        val err = json.optString("error", "request_failed")
        val hint = json.optString("hint", "")
        store.appendMessage("error", listOf(err, hint).filter { it.isNotBlank() }.joinToString(" — "))
    }
    busy = false
    reloadTranscript()
}
```

**Critical:** Before wiring parse, open `HostAiClient.kt` and match the real success JSON keys (`ok`, `text` / `content` / `reply`).

**Step 3: Commit**

```bash
git add android/app/src/main/java/io/grokify/os/apps/GrokAssistant.kt
git commit -m "feat(assistant): Chat tab, Grok Build complete, optional TTS"
```

---

### Task 6: Compile, version bump, publish OTA

**Files:**
- Modify: `android/app/build.gradle.kts` (`versionCode` / `versionName`)

**Step 1: Compile**

```bash
cd /root/grokifyos/android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -50
```

Expected: BUILD SUCCESSFUL. Fix any errors.

**Step 2: Bump version**

Current at design time: `versionCode = 161`, `versionName = "0.1.161"`.  
Bump to next: e.g. `162` / `0.1.162` (or whatever is current at implement time + 1).

**Step 3: Publish debug OTA** (per AGENTS.md)

```bash
cd /root/grokifyos/android && ./scripts/publish.sh debug --changelog "Grok Assistant inner app: chat, modes, prompts, TTS"
```

**Step 4: Commit version + any fixups**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/io/grokify/os/apps/
git commit -m "Android 0.1.162: Grok Assistant MVP inner app"
```

**Step 5: Manual smoke checklist**

- [ ] Apps hub shows Grok Assistant  
- [ ] Master off → Send blocked  
- [ ] Conversation send → assistant bubble  
- [ ] Dev mode changes system framing  
- [ ] Speak on → audio (with vault key or device TTS)  
- [ ] Edit core prompt → Save → Reset restores  
- [ ] Relaunch app → transcript still there  
- [ ] Coming soon rows not clickable  

---

## Reference map

| Need | Look here |
|---|---|
| Host module registration | `BuiltinPluginCatalog.kt` |
| Pane routing | `GrokifyAppRoot.kt` → `AppsPane` |
| LLM complete | `HostAiClient.complete` |
| TTS | `HostAiClient.speak`, `GROK_VOICES` in `SpotifyLiveDj.kt` |
| DJ prompt UI patterns | `SpotifyController.kt` prompt templates section + `SpotifyDjPrompts.kt` |
| Vault key | `ApiKeyIds.SPACEXAI` / `HostApiKeyStore` |
| Smaller host module UI sample | `SpaceXaiUsageAnalyzer.kt` |

## Out of scope (do not implement)

Wake word, overlay, ROLE_ASSISTANT, Android Auto, screen capture crop, real tool execution.
