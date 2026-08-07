# Architecture

GrokifyOS is an **open-source, self-hosted** stack for a Grokify-style AI assistant: web UI, REST API, agent bridge, and Android client. It is designed so anyone can run their own instance with **Grok Build**.

## Components

```text
┌─────────────┐     HTTPS / LAN      ┌──────────────────┐
│  Browser    │ ───────────────────► │  PHP web + API   │
│  Phone app  │ ───────────────────► │  (web/)          │
│  Wear app   │ ───────────────────► │  multi-channel   │
└──────┬──────┘                      │  OTA store       │
       │ Data Layer (BT)             └────────┬─────────┘
       │ keys + device token                  │
       ▼                                      │
  Galaxy Watch / Wear OS         MySQL ◄──────┤
                                              │
                     ┌────────────────────────▼─────────┐
                     │  Node bridge (WebSocket agents)  │
                     │  Grok Build / CLI auth           │
                     └──────────────────────────────────┘
```

| Area | Scope |
|------|--------|
| **Web dashboard** | Password login, device pairing, chat UI, APK upload |
| **REST APIs** | Auth, devices, chat sessions/messages, models, usage, multi-channel OTA (`phone` / `wear` / `wear-face`) |
| **Bridge** | Agent WebSocket gateway (own port + install workspace; agent `cwd` selectable via `bridge_agent_cwd`) |
| **Phone Android** | Package `io.grokify.os` — Chat, Apps hub, Watch Deploy, OTA |
| **Wear Android** | Same package id as phone; standalone HUD + Carina; self-update `channel=wear` |
| **Watch face** | Separate package `io.grokify.os.wear.face` (WFF) |
| **Schema** | Users (password hash), chat tables, devices, APK releases |
| **Auth** | Username/password sessions + device Bearer tokens (`gos_…`) |

## Auth

| Audience | Mechanism |
|----------|-----------|
| Browser admin | Session cookie after password login |
| Android / API clients | Device token `gos_…` from **Devices** in the UI |
| Optional later | Configurable OAuth — not required to install |

## Configuration

| Concern | Convention |
|---------|------------|
| Env vars | `GROKIFY_*` only (see `.env.example`) |
| Database | Dedicated `grokifyos` schema — never share with unrelated apps |
| Site URL | `GROKIFY_SITE_URL` (local `http://…` or public `https://…`) |
| Bridge | `GROKIFY_BRIDGE_URL` / `GROKIFY_WS_PATH` / `GROKIFY_WS_AUTH_SECRET` |
| Bridge install workspace | `GROKIFY_WORKSPACE` (logs, runtime, uploads) — not the same as agent cwd |
| Agent working directory | `app_settings.bridge_agent_cwd` (empty → install workspace); UI in Android/web Settings; bridge `/work-dir` |
| Grok Build | `GROKIFY_GROK_AUTH_JSON` → real usage and agents |

## Local vs remote

| Mode | Typical setup |
|------|----------------|
| **Local / LAN** | PHP built-in server + MySQL on the same machine; phone uses LAN IP |
| **VPS** | Apache/nginx + TLS; bridge on localhost; WS reverse-proxied |

Same codebase either way — only DNS, TLS, and `.env` URLs change.

## Data & privacy

- All chat and device data live in **your** MySQL and `storage/`.
- Grok Build / xAI is called only with **your** `auth.json` credentials.
- No phone-home to a central GrokifyOS service.

## Non-goals

- Not a multi-tenant SaaS control plane
- Not a fork that must stay in lockstep with other private forks or products
- No synthetic chat sessions or invented usage numbers
