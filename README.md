# GrokifyOS

**Open-source stack to run your own Grokify-style AI assistant** — web dashboard, Android app, and agent bridge — powered by [Grok Build](https://grok.com) (xAI).

Self-host on a laptop, home lab, or VPS. Pair phones with device tokens. Chat with real agents; usage comes from your Grok Build account (no fake numbers).

| | |
|--|--|
| **Auth** | Username + password (browser); device Bearer tokens `gos_…` (Android) |
| **Stack** | PHP 8.1+, MySQL/MariaDB, Node 18+ (bridge), optional Android SDK |
| **Deploy** | Local / LAN **or** remote VPS with HTTPS |
| **License** | [MIT](LICENSE) |

> GrokifyOS is a standalone product. It does **not** require or modify any private Grokpot deployment.

## What you get

- **Web dashboard** — login, chat sessions, notes, device pairing, APK release upload
- **REST APIs** — auth, devices, chat, models, live Grok Build usage
- **Agent bridge** — WebSocket gateway for streaming agents
- **Android app** — package `io.grokify.os` (Compose); side-by-side with other installs

## Quick start

```bash
git clone https://github.com/iBerry420/grokifyos.git
cd grokifyos
cp .env.example .env
# edit .env → MySQL credentials (see docs/INSTALL.md)

php scripts/install.php --admin=admin --password='your-long-password'

# local / LAN (phones on same Wi‑Fi can use http://YOUR_LAN_IP:8787)
php -S 0.0.0.0:8787 scripts/dev-router.php
```

Open `http://127.0.0.1:8787` → sign in → **Devices** → create a `gos_…` token for the Android app.

Full install (Ubuntu, Windows, macOS), bridge, TLS, and Android: **[docs/INSTALL.md](docs/INSTALL.md)**.  
Scope and architecture: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## Repository layout

```text
web/           PHP app (public UI, API, includes, assets)
schema/        SQL schema (greenfield install)
bridge/        Node WebSocket agent gateway
android/       Kotlin + Compose client (io.grokify.os)
scripts/       install.php, dev router
deploy/        Apache vhost + systemd unit examples
docs/          install + architecture
storage/       sessions, bridge runtime, APKs (gitignored contents)
```

## Auth

| Client | Method |
|--------|--------|
| Browser | Username + password → session cookie `__grokifyos_sid` |
| Android | Device Bearer `gos_…` minted in the web UI after login |

Password-only by design for simple self-hosting. Optional OAuth can be added later as pure config — not required to run.

## Grok Build

Point the app at a real Grok Build login so usage and agents work against your account:

```env
GROKIFY_GROK_AUTH_JSON=/path/to/auth.json   # from `grok login`
```

If auth is missing, APIs return a clear error — they never invent usage stats.

## Features

| Area | Status |
|------|--------|
| Health, first-admin setup, login, session | Ready |
| Device token create / list / revoke | Ready |
| Chat REST + dashboard UI | Ready |
| Bridge (WebSocket agents) | Ready |
| Android package + OTA download | Ready |
| Grok Build live usage | Ready (needs `auth.json`) |

## Security

- Never commit `.env`, `storage/sessions/*`, `storage/apk/*`, or `auth.json`
- Use HTTPS on any host reachable from the internet
- Use strong DB password and `GROKIFY_WS_AUTH_SECRET`
- Keep `storage/` writable only by the web/bridge user

## Contributing

Issues and PRs welcome. Prefer small, focused changes. Keep secrets out of the tree.

## License

[MIT](LICENSE) — use it, fork it, host it.
