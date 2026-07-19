# Grok Assistant — Design (MVP)

**Date:** 2026-07-20  
**Status:** Approved (MVP scope)  
**Module id:** `grok_assistant`

## Goal

Ship a new built-in inner app, **Grok Assistant**, that lets the user enable/disable assistant features, pick **Conversation** vs **Dev** mode, edit/reset system prompt templates, chat with Grok Build (same host bridge as main Chat), and speak replies with the **same TTS stack as Spotify Live DJ** (vault `spacexai_api_key` + `HostAiClient.speak` + `GROK_VOICES`).

## Non-goals (post-MVP)

- Continuous “hey Grok” wake word / always-listening
- System default assistant role, BT headset assistant buttons, Android Auto hooks
- Overlay mini UI on top of other apps
- Screen “look at my screen” + crop
- Real host tool loop / file edit execution (Dev mode is **prompt framing only** in v1)

These appear in Setup as **Coming soon** disabled rows so the product roadmap is visible.

## Approach

**Host module + own store** (mirrors Spotify / Live DJ patterns):

| Piece | Pattern |
|---|---|
| Catalog entry | `BuiltinPluginCatalog` host module |
| UI | Compose pane in `GrokifyAppRoot.AppsPane` |
| Prefs | `SharedPreferences` via `GrokAssistantStore` |
| Prompts | `GrokAssistantPrompts` (defaults + edit/reset like `SpotifyDjPrompts`) |
| Chat LLM | `HostAiClient.complete` with built system string + session title |
| TTS | `HostAiClient.speak` + same voice chips / prefer-device as Live DJ |
| Keys | Vault `spacexai_api_key` (optional for TTS; chat uses device token / Grok Build) |

No new network stack. No background mic in v1.

## Architecture

```
Apps hub → GrokAssistantPane
              ├── Chat tab  (transcript + composer + optional speak)
              └── Setup tab (master, mode, voice, prompts, coming-soon)
                        │
                        ▼
              GrokAssistantStore (SharedPreferences)
                        │
         ┌──────────────┼──────────────────┐
         ▼              ▼                  ▼
   system string   HostAiClient.complete  HostAiClient.speak
   (prompts)       (Grok Build bridge)    (xAI TTS / device)
```

### Files (planned)

| File | Role |
|---|---|
| `apps/plugin/BuiltinPluginCatalog.kt` | Register `GROK_ASSISTANT` |
| `apps/GrokAssistantPrompts.kt` | Kinds, defaults, JSON encode/decode, reset helpers |
| `apps/GrokAssistantStore.kt` | Prefs: enabled, mode, voice, speak, prefer device, templates, transcript |
| `apps/GrokAssistant.kt` | Compose pane (Chat + Setup), send pipeline, TTS preview |
| `ui/GrokifyAppRoot.kt` | Route host module → pane |
| `apps/plugin/PluginModels.kt` | Optional new `PluginIconKey` if needed (else reuse `Apps` / `Extension`) |
| `apps/plugin/RemotePluginCatalog.kt` | Icon parse alias if new key added |

### Session / API

- `HostAiClient.complete(ctx, userText, optionsJson)` with:
  - `system`: assembled prompt (see below)
  - `session_title`: `"Grok Assistant"` (dedicated plugin session, not main Chat)
- Model: host default / preferred (do not hardcode unless options already support override)
- Device must be signed in (same as other plugins); surface `not_signed_in` clearly

### TTS

Reuse Live DJ surface:

- Voice list: `GROK_VOICES` from `SpotifyLiveDj.kt`
- Options JSON for speak: `voice_id`, `prefer_device`, `language` (default `en`)
- Speak only when `speak_replies` is on and assistant is enabled
- Soft-fail: chat bubble still shown; toast on speak failure
- Missing vault key: chat still works; Setup Voice row shows banner / status

## Data model

### Store keys (`grok_assistant_prefs`)

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `false` |
| `mode` | string `conversation` \| `dev` | `conversation` |
| `voice_id` | string | `eve` |
| `prefer_device_tts` | boolean | `false` |
| `speak_replies` | boolean | `true` |
| `prompt_templates_v1` | JSON array | built-in defaults |
| `transcript_v1` | JSON array of messages | `[]` |

### Message

```json
{ "id": "uuid", "role": "user|assistant|system|error", "text": "...", "ts": 0 }
```

- Cap stored transcript: last **100** messages
- Model context window: last **12 turns** (≤24 messages) or ~6k chars, whichever smaller

### Prompt kinds

| Kind | Selection | Ids |
|---|---|---|
| **Core** | always one body | `core_identity` |
| **Mode** | one of conversation / dev | `mode_conversation`, `mode_dev` |
| **Extra** | multi-toggle | `style_concise`, `style_witty`, `style_spoken` |

Template fields (same spirit as DJ): `id`, `kind`, `label`, `blurb`, `body`, `enabled`, `builtIn`.

**Runtime system string:**

```
[core body]
---
[active mode body]
---
[each enabled extra body]
---
Mode: conversation|dev · Speak replies: on|off
```

If `speak_replies` and spoken style not already covered, append:  
*Reply in plain speech-friendly prose; avoid code fences unless user asked for code.*

**Reset:** restores stock body/label/blurb for that built-in `id` only.  
**Add:** custom extras only (not a second core/mode).

### Default prompt bodies (ship as constants)

**core_identity** — On-device Grok Assistant for GrokifyOS. Concise, helpful, never claim tools/files succeeded when they didn’t. Stay in character. No fake system access.

**mode_conversation** — Everyday Q&A; warm and direct; clarify when ambiguous.

**mode_dev** — Engineering partner: code, debugging, file/tool *reasoning*. State clearly when an action needs host tools not yet wired. Prefer concrete steps and diffs when useful.

**style_*** — short style constraints as named.

## UI

### Shell

- Host module title **Grok Assistant**, accent **Violet**, capabilities: AI, Voice, Chat
- Top: back + title; segmented **Chat | Setup**
- Required keys: optional `spacexai_api_key` (TTS only)

### Chat tab

- Transcript: user right, assistant left, error full-width muted/warn
- Composer: multiline + Send; disabled when `!enabled` or `busy`
- Speak replies quick toggle (same pref as Setup)
- Clear: confirm → wipe local transcript only
- Empty state: blurb + “Turn on in Setup” if master off
- Hold-to-talk: **out of v1** (optional later)

### Setup tab (top → bottom)

1. Master — Assistant enabled  
2. Mode — Conversation | Dev  
3. Voice — `GROK_VOICES` chips, vault status, Prefer device TTS, Speak replies, Preview  
4. Prompts — sections by kind; editor sheet; Reset / Add  
5. Coming soon (disabled) — wake word · overlay · default assistant / BT / Auto  

No API key form in-pane — point to vault Settings if missing.

## Send pipeline

1. Guard: `enabled`, not `busy`, non-blank text  
2. Append user message → persist  
3. `busy = true`  
4. Build system string from store  
5. Build history window for optional inclusion in prompt (user/assistant only)  
6. `HostAiClient.complete` on IO dispatcher  
7. On ok: append assistant message; if `speak_replies`, `HostAiClient.speak`  
8. On fail: append error message  
9. `busy = false`; cap transcript  

Concurrent Send ignored while busy.

## Error handling

| Condition | UI |
|---|---|
| Master off + Send | Inline / toast: enable in Setup |
| Not signed in | Error bubble |
| Missing TTS key | Chat works; Setup banner; speak soft-fail or device TTS |
| Network / complete fail | Error bubble; re-enable composer |
| Empty model reply | Error bubble |
| Speak failure | Toast; chat kept |

## Testing

- Pure unit tests (JVM) for: system string assembly, template reset merge, transcript cap, history window trim  
- Manual: open pane from hub, toggle master, Conversation/Dev, send chat, speak on/off, edit/reset prompt, missing vault key path  

## Success criteria (MVP)

1. Apps hub shows **Grok Assistant**; opens Chat + Setup  
2. Master off blocks send with clear UX  
3. Mode switches change system prompt (verifiable in network notes / behavior)  
4. Chat uses Grok Build when signed in  
5. Speak replies uses same voices/TTS as Live DJ  
6. Built-in prompts editable + reset to defaults  
7. Transcript persists across process death (prefs)  
8. Coming-soon rows visible but non-actionable  

## Follow-ups (not this PR)

Phase 2: wake word service + mic lifecycle  
Phase 3: overlay permission + mini UI + crop screenshot  
Phase 4: `ROLE_ASSISTANT` / BT / Auto integration  
Phase 5: Dev mode real tools (agent capabilities gate)
