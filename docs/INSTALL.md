# GrokifyOS install

Password-only admin auth. Dedicated MySQL database. Production chat (sessions, messages, notes, audit, Grok Build usage) — **no demo seed data**.

Does **not** touch your private Grokpot/Grokify stack.

## Requirements

| Component | Notes |
|-----------|--------|
| PHP 8.1+ | `pdo_mysql`, `curl`, `json`, `mbstring`, `session` |
| MySQL 8+ / MariaDB 10.5+ | Dedicated database |
| Node 18+ | Optional — only if you run the agent bridge |
| Android SDK | Optional — only to build the APK |

## 1. Clone & configure

```bash
git clone git@github.com:iBerry420/grokifyos.git
cd grokifyos
cp .env.example .env
# Edit .env: DB credentials, bridge URL, WS secret, auth.json path
```

## 2. Database

```bash
# Create MySQL user + database first, then:
php scripts/install.php --admin=YOUR_USER --password=YOUR_LONG_PASSWORD
```

Applies `schema/*.sql` (idempotent). Creates the first admin only when the users table is empty.

## 3. Run web (local LAN)

```bash
# Dev server (LAN-only; phones on the same Wi‑Fi can use http://YOUR_LAN_IP:8787)
php -S 0.0.0.0:8787 scripts/dev-router.php
```

Or point Apache/nginx DocumentRoot at `web/public` and alias `/api` → `web/api`, `/assets` → `web/assets` (see `deploy/apache-vhost.conf.example`).

| Mode | Reachability |
|------|----------------|
| **Local / LAN** | Same network only |
| **VPS + DNS + TLS** | Anywhere |

### VPS example (this host)

Live test host on the Contabo VPS:

| Item | Value |
|------|--------|
| URL | https://grokifyos.grokpot.io |
| App path | `/var/www/grokifyos` → `/root/grokifyos` |
| Apache | `sites-available/grokifyos.grokpot.io.conf` + `-le-ssl.conf` |
| Env (Apache) | `/etc/grokifyos/php.env` (mode `640`, group `www-data`) — **not** in git |
| TLS | Let’s Encrypt via `certbot --apache -d grokifyos.grokpot.io` |

```bash
# DNS A record → VPS IP, then:
sudo ln -sfn /path/to/grokifyos /var/www/grokifyos
sudo cp deploy/apache-vhost.conf.example /etc/apache2/sites-available/grokifyos.grokpot.io.conf
# edit ServerName / paths if needed
sudo a2ensite grokifyos.grokpot.io.conf
sudo apache2ctl configtest && sudo systemctl reload apache2
sudo certbot --apache -d grokifyos.grokpot.io --redirect
# put secrets in /etc/grokifyos/php.env; chown root:www-data; chmod 640
# storage must be writable: chown -R www-data:www-data storage/
```

HTTP redirects to HTTPS (ACME challenge path excluded). Auto-renew is handled by certbot’s timer.

## 4. Bridge (real agents)

Chat **persists** without a bridge (sessions/messages in MySQL). **Streaming agents** need the Node bridge:

```bash
cd bridge
npm ci
# Configure env for GrokifyOS (own port, e.g. 8876 — do not steal production 8766 long-term)
export GROKIFY_BRIDGE_PORT=8876   # or project-specific vars your bridge expects
node server.js
```

Set in `.env`:

```env
GROKIFY_BRIDGE_URL=http://127.0.0.1:8876
GROKIFY_BRIDGE_HEALTH=http://127.0.0.1:8876/health
GROKIFY_WS_PATH=/grokify-ws/
GROKIFY_WS_AUTH_SECRET=long-random-string
```

Proxy `GROKIFY_WS_PATH` (WebSocket) from your reverse proxy to the bridge.

## 5. Grok Build usage (optional but real)

Usage chip calls xAI billing with a real OAuth token:

```env
GROKIFY_GROK_AUTH_JSON=/path/to/auth.json   # from `grok login`
```

If auth is missing, the API returns a clear error — it does **not** invent usage numbers.

## 6. Android APK

- Source: `android/` (package rename to `io.grokify.os` is a later phase)
- Upload a real APK from the dashboard **Build** tab, or place via `web/api/apk-upload.php`
- Download: `/api/apk-download.php`
- Device tokens: dashboard **Devices** → `gos_…` tokens

## API surface (Phase 2)

| Path | Role |
|------|------|
| `/api/health.php` | Health + chat readiness |
| `/api/setup.php` / `login.php` / `logout.php` / `me.php` | Password auth |
| `/api/devices.php` | Device token mint/list/revoke |
| `/api/admin-system-chat-sessions.php` | Chat sessions |
| `/api/admin-system-chat-messages.php` | Messages (CRUD + stream_upsert) |
| `/api/admin-system-chat-notes.php` | Instruction notes |
| `/api/admin-system-chat-models.php` | Models + WS token |
| `/api/admin-system-chat-audit.php` | Audit list / SSE stream |
| `/api/admin-system-chat-usage.php` | Live Grok Build usage |
| `/api/apk-upload.php` / `apk-download.php` | APK releases |

## Security checklist

- Never commit `.env`, `storage/sessions/*`, `storage/apk/*`, or `auth.json`
- Use HTTPS on any public host
- Keep `GROKIFY_WS_AUTH_SECRET` and DB password strong
- Private repo recommended until you intentionally open-source
