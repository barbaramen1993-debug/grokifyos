# GrokifyOS install (Phase 1)

Password-only admin auth. Dedicated MySQL database. Does **not** touch your private Grokpot/Grokify stack.

## Requirements

- PHP 8.2+ with `pdo_mysql`, `json`, `session`
- MySQL 8+ / MariaDB 10.6+
- Node 20+ (bridge; optional for login shell only)
- Apache or nginx (document root → `web/public`)

## 1. Configure

```bash
cd /path/to/grokifyos
cp .env.example .env
# edit GROKIFY_DB_* and GROKIFY_SITE_URL
```

Create a MySQL user/database (example):

```sql
CREATE DATABASE grokifyos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'grokifyos'@'localhost' IDENTIFIED BY 'strong-password';
GRANT ALL ON grokifyos.* TO 'grokifyos'@'localhost';
FLUSH PRIVILEGES;
```

## 2. Schema + optional admin CLI

```bash
php scripts/install.php
# or:
php scripts/install.php --admin=admin --password='your-long-password'
```

If you skip CLI admin create, open the site once and use **Create admin** in the UI.

## 3. Web server

Point the vhost document root at `web/public`, and alias:

| URL path | Filesystem |
|----------|------------|
| `/` | `web/public/` |
| `/api` | `web/api/` |
| `/assets` | `web/assets/` |

Example Apache snippet: `deploy/apache-vhost.conf.example`.

## 4. Smoke test

```bash
curl -sS https://your-host/api/health.php | jq .
# → ok, needs_setup, db.ok
```

Login UI: `/`  
Device token: after login, **Create device token** (shown once).

## 5. Bridge (later phase)

`bridge/` is copied from the private stack and still needs env renames (`GROKIFY_*`) and its own systemd unit + port (default suggestion: **8876** so it never collides with production 8766). Do not point Phase 1 at production bridge/DB.

## 6. Android (later phase)

`android/` is a source copy. For OSS:

- `applicationId` → `io.grokify.os`
- API base → your GrokifyOS URL
- Token prefix expectation → `gos_`

Your private APK (`io.grokpot.grokify`) stays on the monorepo path.

## Security notes

- Never commit `.env`
- Use HTTPS in production
- Device tokens are shown once; store hashed only (`token_hash`)
- Phase 1 has no rate limiting beyond a short login delay — add reverse-proxy limits before public internet exposure
