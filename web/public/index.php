<?php

declare(strict_types=1);

require_once dirname(__DIR__) . '/includes/bootstrap.php';

$settings = require dirname(__DIR__) . '/includes/settings.php';
$appName = htmlspecialchars((string) ($settings['app_name'] ?? 'GrokifyOS'), ENT_QUOTES, 'UTF-8');
$user = gos_current_user();
$needsSetup = gos_needs_setup();
$base = htmlspecialchars(gos_web_base(), ENT_QUOTES, 'UTF-8');
?><!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title><?= $appName ?></title>
  <style>
    :root {
      --bg: #0b0d12;
      --panel: #12161f;
      --border: #1e2636;
      --text: #e8ecf4;
      --muted: #8b95a8;
      --accent: #6ee7ff;
      --accent2: #a78bfa;
      --danger: #f87171;
      --ok: #34d399;
      --input: #0f131a;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
      background:
        radial-gradient(1200px 600px at 10% -10%, rgba(110,231,255,.12), transparent 50%),
        radial-gradient(900px 500px at 100% 0%, rgba(167,139,250,.10), transparent 45%),
        var(--bg);
      color: var(--text);
    }
    .wrap { max-width: 420px; margin: 0 auto; padding: 48px 20px 64px; }
    .brand { display: flex; align-items: center; gap: 12px; margin-bottom: 28px; }
    .brand-mark {
      width: 40px; height: 40px; border-radius: 12px;
      background: linear-gradient(135deg, var(--accent), var(--accent2));
      box-shadow: 0 0 24px rgba(110,231,255,.25);
    }
    .brand h1 { font-size: 1.25rem; margin: 0; font-weight: 650; letter-spacing: -0.02em; }
    .brand p { margin: 2px 0 0; color: var(--muted); font-size: .8rem; }
    .card {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 24px;
      box-shadow: 0 20px 50px rgba(0,0,0,.35);
    }
    label { display: block; font-size: .8rem; color: var(--muted); margin: 14px 0 6px; }
    input {
      width: 100%;
      padding: 12px 14px;
      border-radius: 10px;
      border: 1px solid var(--border);
      background: var(--input);
      color: var(--text);
      font-size: 1rem;
    }
    input:focus { outline: 2px solid rgba(110,231,255,.35); border-color: transparent; }
    button {
      width: 100%;
      margin-top: 20px;
      padding: 12px 16px;
      border: 0;
      border-radius: 10px;
      font-weight: 600;
      font-size: .95rem;
      cursor: pointer;
      color: #041016;
      background: linear-gradient(135deg, var(--accent), #7dd3fc);
    }
    button.secondary {
      background: transparent;
      color: var(--muted);
      border: 1px solid var(--border);
      margin-top: 10px;
    }
    .msg { margin-top: 14px; font-size: .85rem; min-height: 1.2em; }
    .msg.err { color: var(--danger); }
    .msg.ok { color: var(--ok); }
    .dash h2 { margin: 0 0 8px; font-size: 1.1rem; }
    .dash .meta { color: var(--muted); font-size: .85rem; line-height: 1.5; }
    .pill {
      display: inline-block;
      margin-top: 12px;
      padding: 4px 10px;
      border-radius: 999px;
      font-size: .75rem;
      background: rgba(110,231,255,.12);
      color: var(--accent);
      border: 1px solid rgba(110,231,255,.25);
    }
    .token-box {
      margin-top: 12px;
      padding: 10px 12px;
      background: var(--input);
      border: 1px solid var(--border);
      border-radius: 8px;
      font-family: ui-monospace, monospace;
      font-size: .75rem;
      word-break: break-all;
      display: none;
    }
    .hint { color: var(--muted); font-size: .8rem; margin-top: 16px; line-height: 1.45; }
    a { color: var(--accent); }
  </style>
</head>
<body>
  <div class="wrap">
    <div class="brand">
      <div class="brand-mark" aria-hidden="true"></div>
      <div>
        <h1><?= $appName ?></h1>
        <p>Self-hosted AI assistant · password auth</p>
      </div>
    </div>

    <?php if ($user): ?>
    <div class="card dash" id="dash">
      <h2>Signed in as <?= htmlspecialchars((string) ($user['display_name'] ?: $user['username']), ENT_QUOTES, 'UTF-8') ?></h2>
      <div class="meta">
        Role: <?= htmlspecialchars((string) $user['role'], ENT_QUOTES, 'UTF-8') ?><br>
        Username: <?= htmlspecialchars((string) $user['username'], ENT_QUOTES, 'UTF-8') ?>
      </div>
      <span class="pill">Phase 1 shell · chat APIs next</span>
      <p class="hint">
        Create a device token for the Android app. Paste it once into the APK settings.
      </p>
      <button type="button" id="btn-device">Create device token</button>
      <div class="token-box" id="token-box"></div>
      <button type="button" class="secondary" id="btn-logout">Sign out</button>
      <div class="msg" id="msg"></div>
    </div>
    <?php else: ?>
    <div class="card" id="auth-card">
      <div id="mode-label" style="font-size:.9rem;color:var(--muted);">
        <?= $needsSetup ? 'Create the first admin account' : 'Sign in' ?>
      </div>
      <form id="auth-form" autocomplete="on">
        <label for="username">Username</label>
        <input id="username" name="username" required minlength="3" maxlength="32" pattern="[a-zA-Z0-9_]+" autocomplete="username">
        <label for="password">Password</label>
        <input id="password" name="password" type="password" required minlength="8" autocomplete="<?= $needsSetup ? 'new-password' : 'current-password' ?>">
        <?php if ($needsSetup): ?>
        <label for="display_name">Display name (optional)</label>
        <input id="display_name" name="display_name" maxlength="128" autocomplete="nickname">
        <?php endif; ?>
        <button type="submit" id="btn-submit"><?= $needsSetup ? 'Create admin' : 'Sign in' ?></button>
      </form>
      <div class="msg" id="msg"></div>
      <p class="hint">
        Auth is <strong>password only</strong> (no OAuth). Health:
        <a href="<?= $base ?>/api/health.php">/api/health.php</a>
      </p>
    </div>
    <?php endif; ?>
  </div>
  <script>
    const base = <?= json_encode(gos_web_base()) ?>;
    const needsSetup = <?= $needsSetup ? 'true' : 'false' ?>;
    const msg = document.getElementById('msg');

    async function api(path, opts = {}) {
      const res = await fetch(base + path, {
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
        ...opts,
      });
      const data = await res.json().catch(() => ({}));
      return { res, data };
    }

    const form = document.getElementById('auth-form');
    if (form) {
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        msg.className = 'msg';
        msg.textContent = '…';
        const body = {
          username: document.getElementById('username').value.trim(),
          password: document.getElementById('password').value,
        };
        const dn = document.getElementById('display_name');
        if (dn) body.display_name = dn.value.trim();
        const path = needsSetup ? '/api/setup.php' : '/api/login.php';
        const { res, data } = await api(path, { method: 'POST', body: JSON.stringify(body) });
        if (!data.ok) {
          msg.className = 'msg err';
          msg.textContent = data.error || ('HTTP ' + res.status);
          return;
        }
        msg.className = 'msg ok';
        msg.textContent = 'OK — reloading…';
        location.reload();
      });
    }

    const btnLogout = document.getElementById('btn-logout');
    if (btnLogout) {
      btnLogout.addEventListener('click', async () => {
        await api('/api/logout.php', { method: 'POST', body: '{}' });
        location.reload();
      });
    }

    const btnDevice = document.getElementById('btn-device');
    if (btnDevice) {
      btnDevice.addEventListener('click', async () => {
        msg.className = 'msg';
        msg.textContent = 'Creating…';
        const { data } = await api('/api/devices.php', {
          method: 'POST',
          body: JSON.stringify({ device_name: 'Android' }),
        });
        if (!data.ok) {
          msg.className = 'msg err';
          msg.textContent = data.error || 'failed';
          return;
        }
        const box = document.getElementById('token-box');
        box.style.display = 'block';
        box.textContent = data.token;
        msg.className = 'msg ok';
        msg.textContent = 'Token created — copy it now; it will not be shown again.';
      });
    }
  </script>
</body>
</html>
