# GrokifyOS product contract

Private production (this server’s **grokpot** monorepo) and **GrokifyOS** are separate products that share DNA, not one deployable.

## Ships in GrokifyOS

| Area | Scope |
|------|--------|
| Web dashboard | Password login, device pairing, chat UI (ported over phases) |
| REST APIs | Auth, devices, chat sessions/messages, status/health |
| Bridge | Agent WebSocket gateway (own env + workspace) |
| Android | Package `io.grokify.os` (OSS); your private APK stays `io.grokpot.grokify` |
| Schema | `users` (password), system chat tables, devices, APK releases |
| Auth v1 | **Password only** + device Bearer tokens (`gos_…`) |

## Private-only (not in GrokifyOS)

- Grokpot skills / SPA platform
- Discord bot, multi-site admin, moderation
- Grokpot OAuth (Discord/X) as a hard dependency
- Your production DB, sessions, device tokens, APK OTA channel
- Hardcoded `grokpot.io` / `grokify.grokpot.io` defaults

## Direction of truth

1. You develop chat/bridge features primarily in the **private monorepo**.
2. Port deliberately into **GrokifyOS** when ready for self-hosters.
3. Never dual-write every commit automatically.

## Auth matrix

| Audience | Mechanism |
|----------|-----------|
| Your production Grokify | Unchanged: grokpot OAuth + `gf_…` device tokens |
| GrokifyOS v1 | Local username/password + `gos_…` device tokens |
| GrokifyOS later (optional) | Configurable OAuth providers — not required for install |

## Domains & packages

| | You (private) | GrokifyOS |
|--|---------------|-----------|
| Web | current Grokify host | e.g. `grokifyos.…` or self-hoster’s domain |
| Android id | `io.grokpot.grokify` | `io.grokify.os` |
| DB | existing | fresh `grokifyos` |
| Env | `GROKPOT_*` | `GROKIFY_*` only |

## Non-goals for Phase 1

- No cutover of your live chat data
- No changes required in the private monorepo to “enable” OSS
- No public release until INSTALL + license + secret scrub are done
