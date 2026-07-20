# Companion — Design (MVP)

**Date:** 2026-07-20  
**Status:** Approved  
**Module id:** `companion`

## Goal

Ship a new built-in inner app, **Companion**, that pairs a **Live2D (2D anime VTuber) body** with **SpaceXAI Voice Agent** and text chat so the user can talk to and interact with an animated presence — similar in spirit to Grok Companions.

Default personality is **warm friend** (casual, supportive, curious; light humor). The system prompt is **fully customizable**. Voice is primary; typed chat also works and **TTS-speaks** replies. Live2D ships with a **bundled sample model** and optional **load my model** from storage.

## Non-goals (v1)

- Wake word / always-on overlay
- Multi-character gallery or cloud model marketplace
- Native Cubism Live2D SDK (use web stack in WebView)
- Viseme ML / phoneme-accurate lip-sync (amplitude only)
- 3D VRM / full-body Grok Companion parity

## Approach

**Host module + WebView Live2D stage** (recommended over native Cubism or remote WebView plugins):

| Piece | Pattern |
|---|---|
| Catalog entry | `BuiltinPluginCatalog` host module `companion` |
| UI shell | Compose pane (`CompanionApp` / `CompanionPane`) |
| Avatar | Offline WebView: PixiJS + `pixi-live2d-display` (or equivalent) under `assets/companion/` |
| Voice | Reuse `GrokAssistantVoiceClient` (or thin `CompanionVoiceSession` wrapper) |
| Text chat | `HostAiClient.complete` + shared conversation memory |
| TTS (text path) | `HostAiClient.speak` while avatar is in speaking state |
| Prefs | `CompanionStore` (SharedPreferences + files for user model path) |
| Keys | Vault `spacexai_api_key` (same path as Grok Assistant / Live DJ) |

README forbids remote WebView plugin sideload; Companion is a **compiled-in host module** with **bundled** assets (user models load from local files only).

## Architecture

```
Apps hub → Companion pane
├── Live2D stage (WebView)
│     assets/companion/  →  index.html + JS + bundled model
│     optional: user model pack (app files dir) via bridge-approved path
├── Voice session
│     GrokAssistantVoiceClient (PCM in/out)
│     amplitude → JS setMouth / setState
├── Text chat
│     HostAiClient.complete → HostAiClient.speak (TTS)
│     same memory as voice turns
└── CompanionStore
      systemPrompt, voiceId, modelSource, userModelPath, chatHistory
```

### Files (planned)

| File / path | Role |
|---|---|
| `apps/plugin/BuiltinPluginCatalog.kt` | Register `COMPANION` |
| `ui/GrokifyAppRoot.kt` | Route host module → `CompanionPane` |
| `apps/companion/CompanionStore.kt` | Prefs + history + model source |
| `apps/companion/CompanionPrompts.kt` | Warm-friend default + assembly helpers |
| `apps/companion/CompanionVoiceSession.kt` | Thin session over voice client + amplitude |
| `apps/companion/CompanionLive2dStage.kt` | Compose `AndroidView` WebView + JS bridge |
| `apps/companion/CompanionApp.kt` | Main Compose UI (stage, PTT, chat, settings) |
| `apps/companion/CompanionAmplitude.kt` | RMS / mouth mapping (pure, unit-tested) |
| `assets/companion/` | Offline stage HTML/JS/CSS + default Live2D model |
| Unit tests under `android/app/src/test/.../companion/` | Prompt, store, amplitude, history cap |

### Session / API

**Voice (push-to-talk)**  
- SpaceXAI Voice Agent WebSocket via existing `GrokAssistantVoiceClient`  
- System instructions = Companion system prompt (+ short shared history summary if needed)  
- Mic PCM up; TTS PCM down; play via same audio path patterns as Grok Assistant voice session  

**Text**  
- `HostAiClient.complete(ctx, userText, optionsJson)` with `system` = Companion prompt, dedicated `session_title` e.g. `"Companion"`  
- On ok: append assistant text; `HostAiClient.speak` for TTS (voice_id from store)  
- Soft-fail speak: keep chat bubble; toast on failure  

**Shared memory**  
- One `chatHistory` for voice transcripts + text turns so modality switches feel continuous  
- Cap: last **40** messages stored; context window trim for complete (e.g. last ~12 turns)

## Interaction model

| Mode | User action | AI path | Avatar |
|---|---|---|---|
| **Push-to-talk** | Hold mic / avatar | Voice Agent (PCM in/out) | `listening` → `thinking` → `speaking`; mouth from amplitude |
| **Text** | Send in chat | `complete` → `speak` | `thinking` then `speaking` while TTS plays |
| **Interrupt** | Tap stop or new hold | Cancel TTS / end voice turn | Back to `idle` |

Permissions: mic for voice (already granted on device); media/storage only when loading a user model pack.

## Data model

### Store keys (`companion_prefs`)

| Key | Type | Default |
|---|---|---|
| `system_prompt` | string | warm-friend default body |
| `voice_id` | string | e.g. `eve` (reuse `GROK_VOICES` list) |
| `model_source` | string `bundled` \| `user` | `bundled` |
| `user_model_path` | string | `""` (path to pack root / `.model3.json`) |
| `chat_history_v1` | JSON array | `[]` |
| `prefer_device_tts` | boolean | `false` (text-path speak option) |

### Message

```json
{ "id": "uuid", "role": "user|assistant|system|error", "text": "...", "ts": 0, "source": "voice|text" }
```

### Default system prompt (warm friend)

Ship a short editable constant: warm, supportive, curious companion; light humor; concise spoken-friendly replies; never invent tool/file success; stay in character as “Companion.”

**Reset prompt** restores the stock default only (user edits otherwise persist).

## Live2D stage & bridge

### Stage (offline WebView)

- `assets/companion/index.html` + vendor JS (Pixi + live2d display) + CSS  
- Bundled free/open model under `assets/companion/models/default/` (must be redistributable; document license in assets README)  
- User pack: copy or SAF-selected folder into app-private files; load only via host-approved file URI / bridge  

WebView settings: JS on, file access as needed for local assets, no arbitrary network for remote plugin content. Prefer loading stage from `file:///android_asset/companion/index.html`.

### JS bridge (Kotlin ↔ stage)

| Direction | Message | Purpose |
|---|---|---|
| Host → JS | `loadModel(source, path?)` | bundled vs user |
| Host → JS | `setState(idle\|listening\|thinking\|speaking)` | expression / motion preset |
| Host → JS | `setMouth(0..1)` | lip-sync from PCM amplitude |
| Host → JS | `tap()` / `playMotion(name)` | optional reactions |
| JS → Host | `ready` / `modelLoaded` / `error` | lifecycle |
| JS → Host | `avatarTapped` | start/stop PTT from stage |

Bridge pattern: `@JavascriptInterface` object (mirror `WifiMapView` / `GrokifyWifiMap`) + `evaluateJavascript` for host→JS.

### Lip-sync

- RMS amplitude on **outbound TTS PCM** (and optional mic while listening)  
- Throttle updates (~30–60 Hz max) → `setMouth`  
- Pure mapping in `CompanionAmplitude` for unit tests  
- No viseme ML in v1  

### Failure modes

| Condition | Behavior |
|---|---|
| Bad user model | Toast + fall back to bundled |
| WebView crash / OOM | Static placeholder; chat + voice still work |
| Missing SpaceXAI key | Clear banner; chat may still work via host complete; voice/TTS degraded |
| Not signed in | Error in chat / voice start fails with clear message |

## UI

### Shell

- Title **Companion**, accent **Pink** or **Violet** (distinct from Grok Assistant if palette allows)  
- Capabilities: AI, Voice, Chat, Avatar  
- Required keys: optional `spacexai_api_key`  

### Main surface

1. Top bar: back · Companion · settings gear · connection chip (idle / voice / error)  
2. Center: Live2D WebView stage (full width); tap/hold = PTT  
3. Bottom dock: hold-to-talk mic · stop · expand chat  
4. Chat sheet: transcript + composer; assistant replies show text and play TTS  
5. Settings sheet: system prompt editor · voice picker · model source (bundled / load pack) · clear history  

## Error handling

| Condition | UI |
|---|---|
| Mic denied | Prompt permission; disable PTT |
| Voice connect fail | Chip + toast; stay on text path |
| Complete fail | Error bubble in chat |
| Speak fail | Toast; text kept |
| Model load fail | Toast; bundled fallback |

## Testing

- **Unit:** default prompt, store round-trip, history cap, amplitude → mouth mapping, benign state machine transitions if extracted  
- **Manual:** open from Apps hub → bundled model idle → PTT reply + mouth move → text send + TTS → custom prompt change → bad user pack falls back → clear history  

## Success criteria (v1)

1. Installable from Apps hub as **Companion**  
2. Bundled Live2D idles; lip-syncs on voice/TTS  
3. Voice PTT + text chat share one conversation memory  
4. Custom prompt + voice picker + load-my-model work  
5. Version bump + OTA publish per `AGENTS.md`  

## Follow-ups (not this MVP)

- Wake word / floating overlay presence  
- Multi-character slots + expression packs  
- Better lip-sync (visemes)  
- Native Cubism if web stage proves too heavy  
- Companion-specific tools (screen look, etc.)  
- Marketplace / remote model download with trust model  

## Decisions log

| Topic | Choice |
|---|---|
| Body style | Live2D (2D anime VTuber) |
| Personality | Warm friend + fully customizable prompt |
| Input | Voice-first + text chat; TTS on text replies |
| Models | Bundled default + optional user pack |
| Integration approach | Host module + offline WebView stage (not native Cubism, not remote plugin) |
