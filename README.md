# GrokifyOS

Self-hosted AI assistant (web dashboard + Android + agent bridge).

**Phase 2:** password-only auth, production chat dashboard + REST APIs, device tokens, APK releases, real Grok Build usage. **No mock/demo seed data.**

This repository is independent of the private Grokpot monorepo. Production Grokify-on-Grokpot is unchanged.

## Quick start

```bash
cp .env.example .env   # set GROKIFY_DB_*
php scripts/install.php --admin=admin --password='long-password'
# point web server at web/public (+ /api, /assets aliases)
```

See [docs/INSTALL.md](docs/INSTALL.md) and [docs/CONTRACT.md](docs/CONTRACT.md).

## Layout

```text
web/           PHP app (public, api, includes, assets)
schema/        Greenfield SQL
bridge/        Agent WebSocket gateway (port env separately)
android/       Compose app (rename package for OSS builds)
scripts/       install.php
deploy/        example vhost / units
docs/          contract + install
storage/       sessions, runtime, apk
```

## Auth (v1)

| Client | Method |
|--------|--------|
| Browser | Username + password → session cookie `__grokifyos_sid` |
| Android | Device Bearer `gos_…` minted from the web UI after login |

No OAuth in v1. Optional providers may appear later as pure config.

## Status

| Piece | Phase 1 |
|-------|---------|
| Health + setup + login + me | ✅ |
| Device token create/list/revoke | ✅ |
| Chat REST + full dashboard UI | ⏳ port next |
| Bridge HA units renamed | ⏳ |
| Android package + API base | ⏳ |
| Public release polish | ⏳ |
