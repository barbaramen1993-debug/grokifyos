<?php

declare(strict_types=1);

require_once dirname(__DIR__) . '/includes/bootstrap.php';

$settings = require dirname(__DIR__) . '/includes/settings.php';
$appName = (string) ($settings['app_name'] ?? 'GrokifyOS');
$user = gos_current_user();
$needsSetup = gos_needs_setup();
$base = gos_web_base();
$h = static function (string $path) use ($base): string {
    return htmlspecialchars($base . $path, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
};

$canAccess = $user !== null;
$displayName = $canAccess ? (string) ($user['display_name'] ?: $user['username']) : '';
$role = $canAccess ? (string) ($user['role'] ?? '') : '';
$chatReady = gos_system_chat_tables_ready();
$devPack = $canAccess ? gos_devices_for_user((int) $user['id']) : ['devices' => [], 'active' => []];
$activeDevices = $devPack['active'];
$latestApk = $canAccess ? gos_latest_apk() : null;
$assetV = '20260713-isolate-app-sessions';
?><!DOCTYPE html>
<html lang="en" class="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="theme-color" content="#0a0b0e">
  <meta name="color-scheme" content="dark">
  <meta name="robots" content="noindex,nofollow">
  <title><?= htmlspecialchars($appName, ENT_QUOTES) ?></title>
  <link rel="stylesheet" href="<?= $h('/assets/css/app.css') ?>?v=<?= $assetV ?>">
  <link rel="stylesheet" href="<?= $h('/assets/css/system-chat.css') ?>?v=<?= $assetV ?>">
  <link rel="icon" href="<?= $h('/assets/grokify-icon.png') ?>" type="image/png">
  <style>
    :root {
      --gf-bg: #0a0b0e;
      --gf-surface: #12151a;
      --gf-card: #181b20;
      --gf-border: #272b31;
      --gf-muted: #9ca3af;
      --gf-faint: #6b7280;
      --gf-accent: #4ade80;
      --gf-accent-dim: rgba(74, 222, 128, 0.12);
      --safe-b: env(safe-area-inset-bottom, 0px);
      --safe-t: env(safe-area-inset-top, 0px);
      --gf-sidebar: 14.5rem;
      --gf-header-h: 3.75rem;
    }
    * { box-sizing: border-box; }
    html, body {
      margin: 0; min-height: 100%;
      background: var(--gf-bg); color: #e5e7eb;
      font-family: Inter, system-ui, -apple-system, sans-serif;
      -webkit-tap-highlight-color: transparent;
    }
    body {
      padding-top: var(--safe-t);
      padding-bottom: calc(3.5rem + var(--safe-b));
    }
    .gf-app { min-height: 100dvh; }
    .gf-shell {
      max-width: 960px; margin: 0 auto;
      padding: 0 0.75rem 1.5rem;
    }
    .gf-header {
      position: sticky; top: 0; z-index: 40;
      background: rgba(10, 11, 14, 0.92);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--gf-border);
      margin: 0 -0.75rem; padding: 0.75rem 0.75rem;
      min-height: var(--gf-header-h);
    }
    .gf-header-inner {
      display: flex; align-items: center; gap: 0.75rem;
      max-width: 960px; margin: 0 auto;
    }
    .gf-logo {
      width: 2.25rem; height: 2.25rem; border-radius: 0.65rem;
      background: linear-gradient(145deg, #1f2937, #0f1115);
      border: 1px solid var(--gf-border);
      display: flex; align-items: center; justify-content: center;
      color: var(--gf-accent); font-weight: 700; font-size: 0.85rem;
      overflow: hidden; flex-shrink: 0;
    }
    .gf-logo img { width: 100%; height: 100%; object-fit: cover; }
    .gf-title { flex: 1; min-width: 0; }
    .gf-title h1 { margin: 0; font-size: 1.05rem; font-weight: 650; color: #fff; letter-spacing: -0.02em; }
    .gf-title p { margin: 0.1rem 0 0; font-size: 0.72rem; color: var(--gf-faint); }
    .gf-badge {
      font-size: 0.65rem; padding: 0.2rem 0.45rem; border-radius: 999px;
      border: 1px solid var(--gf-border); color: var(--gf-muted); white-space: nowrap;
    }
    .gf-badge.ok { border-color: rgba(74,222,128,.35); color: var(--gf-accent); background: var(--gf-accent-dim); }
    .gf-badge.warn { border-color: rgba(251,191,36,.35); color: #fbbf24; }
    .gf-nav {
      position: fixed; left: 0; right: 0; bottom: 0; z-index: 50;
      display: flex; justify-content: space-around;
      padding: 0.35rem 0.5rem calc(0.35rem + var(--safe-b));
      background: rgba(18, 21, 26, 0.96);
      border-top: 1px solid var(--gf-border);
      backdrop-filter: blur(12px);
    }
    .gf-nav button {
      flex: 1; max-width: 7rem;
      display: flex; flex-direction: column; align-items: center; gap: 0.15rem;
      background: none; border: none; color: var(--gf-faint);
      font-size: 0.65rem; padding: 0.35rem; cursor: pointer;
      border-radius: 0.5rem;
    }
    .gf-nav button:hover { color: #e5e7eb; }
    .gf-nav button.active { color: var(--gf-accent); }
    .gf-main { min-width: 0; }
    .gf-panel { display: none; padding-top: 1rem; }
    .gf-panel.active { display: block; }
    .gf-card {
      background: var(--gf-card); border: 1px solid var(--gf-border);
      border-radius: 1rem; padding: 1rem; margin-bottom: 0.85rem;
    }
    .gf-card h2 {
      margin: 0 0 0.65rem; font-size: 0.8rem; font-weight: 600;
      color: #fff; letter-spacing: 0.02em; text-transform: uppercase;
    }
    .gf-muted { color: var(--gf-muted); font-size: 0.85rem; line-height: 1.45; }
    .gf-faint { color: var(--gf-faint); font-size: 0.75rem; }
    .gf-code {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.8rem; background: #0f1115; border: 1px solid var(--gf-border);
      border-radius: 0.5rem; padding: 0.75rem 0.85rem; color: var(--gf-accent);
      overflow-x: auto; white-space: pre; margin: 0.5rem 0 0;
    }
    .gf-btn {
      display: inline-flex; align-items: center; justify-content: center; gap: 0.4rem;
      padding: 0.6rem 1rem; border-radius: 0.65rem; font-size: 0.875rem; font-weight: 600;
      border: 1px solid var(--gf-border); background: #0f1115; color: #fff; cursor: pointer;
      text-decoration: none; min-height: 2.75rem;
    }
    .gf-btn:hover { border-color: #3a4149; }
    .gf-btn:active { transform: scale(0.98); }
    .gf-btn-primary { background: #fff; color: #0f1115; border-color: #fff; }
    .gf-btn-primary:hover { background: #e5e7eb; }
    .gf-btn-accent { background: var(--gf-accent-dim); color: var(--gf-accent); border-color: rgba(74,222,128,.35); }
    .gf-btn-danger { color: #f87171; border-color: rgba(248,113,113,.35); }
    .gf-btn-block { width: 100%; }
    .gf-btn-inline { min-height: auto; padding: 0.35rem 0.75rem; font-size: 0.8rem; }
    .gf-row { display: flex; flex-wrap: wrap; gap: 0.5rem; }
    .gf-input, .gf-textarea, .gf-select {
      width: 100%; background: #0f1115; border: 1px solid var(--gf-border);
      border-radius: 0.55rem; color: #fff; padding: 0.65rem 0.75rem; font-size: 16px;
    }
    .gf-input:focus, .gf-textarea:focus, .gf-select:focus {
      outline: none; border-color: #4b5563;
    }
    .gf-label { display: block; font-size: 0.72rem; color: var(--gf-faint); margin: 0.5rem 0 0.25rem; }
    .gf-token-box {
      font-family: ui-monospace, monospace; font-size: 0.72rem; word-break: break-all;
      background: #0f1115; border: 1px dashed var(--gf-border);
      border-radius: 0.5rem; padding: 0.75rem; color: var(--gf-accent);
    }
    .gf-device {
      display: flex; align-items: flex-start; gap: 0.65rem;
      padding: 0.75rem 0; border-bottom: 1px solid #1f2329;
    }
    .gf-device:last-child { border-bottom: none; padding-bottom: 0; }
    .gf-device-body { flex: 1; min-width: 0; }
    .gf-device-body strong { display: block; color: #fff; font-size: 0.9rem; }
    .gf-stat-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0.55rem; }
    .gf-stat {
      background: #0f1115; border: 1px solid var(--gf-border);
      border-radius: 0.75rem; padding: 0.85rem 1rem;
    }
    .gf-stat .n { font-size: 1.25rem; font-weight: 700; color: #fff; letter-spacing: -0.02em; }
    .gf-stat .l { font-size: 0.68rem; color: var(--gf-faint); margin-top: 0.2rem; text-transform: uppercase; letter-spacing: 0.04em; }
    .gf-home-grid { display: grid; gap: 0; }
    .gf-split { display: grid; gap: 0; }
    #panel-chat.active .sc-root {
      height: calc(100dvh - 9.5rem - var(--safe-b));
      min-height: 360px; max-height: none; border-radius: 1rem;
    }
    .gf-gate {
      min-height: calc(100dvh - 2rem);
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      text-align: center; padding: 2rem 1rem;
    }
    .gf-gate h1 { font-size: 1.75rem; margin: 0.5rem 0; color: #fff; letter-spacing: -0.03em; }
    .gf-gate p { color: var(--gf-muted); max-width: 24rem; line-height: 1.45; }
    .gf-gate-form { width: 100%; max-width: 22rem; text-align: left; margin-top: 1rem; }
    .gf-msg { margin-top: 0.75rem; font-size: 0.85rem; min-height: 1.2em; }
    .gf-msg.err { color: #f87171; }
    .gf-msg.ok { color: var(--gf-accent); }

    @media (min-width: 640px) {
      .gf-stat-grid { grid-template-columns: repeat(4, 1fr); gap: 0.75rem; }
      body { padding-bottom: calc(4rem + var(--safe-b)); }
      .gf-shell { padding: 0 1.25rem 2rem; }
    }

    /* Desktop layout: sidebar + wide main */
    @media (min-width: 960px) {
      body {
        padding-bottom: 0;
        padding-top: 0;
      }
      .gf-app {
        display: grid;
        grid-template-columns: var(--gf-sidebar) minmax(0, 1fr);
        grid-template-rows: auto 1fr;
        min-height: 100dvh;
        max-width: 1440px;
        margin: 0 auto;
      }
      .gf-header {
        grid-column: 1 / -1;
        margin: 0;
        padding: 0.85rem 1.5rem;
        border-bottom: 1px solid var(--gf-border);
      }
      .gf-header-inner {
        max-width: none; margin: 0;
      }
      .gf-title h1 { font-size: 1.15rem; }
      .gf-title p { font-size: 0.78rem; }
      .gf-nav {
        position: sticky;
        top: var(--gf-header-h);
        align-self: start;
        left: auto; right: auto; bottom: auto;
        flex-direction: column;
        justify-content: flex-start;
        gap: 0.25rem;
        padding: 1rem 0.75rem;
        height: calc(100dvh - var(--gf-header-h));
        border-top: none;
        border-right: 1px solid var(--gf-border);
        background: var(--gf-surface);
        backdrop-filter: none;
        overflow-y: auto;
      }
      .gf-nav button {
        flex: 0 0 auto;
        max-width: none;
        width: 100%;
        flex-direction: row;
        justify-content: flex-start;
        gap: 0.65rem;
        font-size: 0.875rem;
        font-weight: 500;
        padding: 0.7rem 0.85rem;
        text-align: left;
        color: var(--gf-muted);
      }
      .gf-nav button:hover {
        background: rgba(255,255,255,0.04);
        color: #fff;
      }
      .gf-nav button.active {
        background: var(--gf-accent-dim);
        color: var(--gf-accent);
      }
      .gf-shell {
        max-width: none;
        width: 100%;
        margin: 0;
        padding: 0 1.75rem 2rem;
        grid-column: 2;
        grid-row: 2;
      }
      .gf-panel { padding-top: 1.25rem; }
      .gf-card {
        padding: 1.25rem 1.35rem;
        margin-bottom: 1rem;
      }
      .gf-card h2 { margin-bottom: 0.85rem; font-size: 0.75rem; }
      .gf-stat .n { font-size: 1.45rem; }
      .gf-home-grid {
        display: grid;
        grid-template-columns: 1.2fr 1fr;
        gap: 1rem;
        align-items: start;
        margin-top: 1rem;
      }
      .gf-home-grid > .gf-card { margin-bottom: 0; }
      .gf-home-grid .gf-about { grid-column: 1 / -1; }
      .gf-split {
        display: grid;
        grid-template-columns: 1fr 1.15fr;
        gap: 1rem;
        align-items: start;
      }
      .gf-split > .gf-card { margin-bottom: 0; }
      .gf-btn { min-height: 2.5rem; padding: 0.55rem 1.1rem; cursor: pointer; }
      .gf-btn-block { width: auto; min-width: 12rem; }
      .gf-input, .gf-textarea, .gf-select { font-size: 0.9rem; max-width: 28rem; }
      #panel-chat.active {
        padding-top: 1rem;
      }
      #panel-chat.active .sc-root {
        height: calc(100dvh - var(--gf-header-h) - 2.5rem);
        min-height: 480px;
      }
      .gf-device {
        align-items: center;
        padding: 0.85rem 0;
      }
    }

    @media (min-width: 1280px) {
      :root { --gf-sidebar: 15.5rem; }
      .gf-app { max-width: 1600px; }
      .gf-shell { padding: 0 2rem 2.5rem; }
      .gf-home-grid { grid-template-columns: 1.35fr 1fr; }
      .sc-msg.user { max-width: min(70%, 42rem); }
      .sc-msg.assistant { max-width: min(85%, 52rem); }
    }
  </style>
</head>
<body>
<?php if (!$canAccess): ?>
  <div class="gf-shell">
    <div class="gf-gate">
      <div class="gf-logo" style="width:3.5rem;height:3.5rem;margin-bottom:0.5rem">
        <img src="<?= $h('/assets/grokify-icon.png') ?>" alt="" width="56" height="56" onerror="this.remove()">
      </div>
      <h1><?= htmlspecialchars($appName, ENT_QUOTES) ?></h1>
      <p>Self-hosted AI assistant. Password-only admin auth. Chat, devices, and OTA APKs — no demo data.</p>
      <form class="gf-gate-form" id="auth-form" autocomplete="on">
        <label class="gf-label" for="username">Username</label>
        <input class="gf-input" id="username" name="username" required minlength="3" maxlength="32" pattern="[a-zA-Z0-9_]+" autocomplete="username">
        <label class="gf-label" for="password">Password</label>
        <input class="gf-input" id="password" name="password" type="password" required minlength="8" autocomplete="<?= $needsSetup ? 'new-password' : 'current-password' ?>">
        <?php if ($needsSetup): ?>
        <label class="gf-label" for="display_name">Display name (optional)</label>
        <input class="gf-input" id="display_name" name="display_name" maxlength="128" autocomplete="nickname">
        <?php endif; ?>
        <button type="submit" class="gf-btn gf-btn-primary gf-btn-block" style="margin-top:1rem">
          <?= $needsSetup ? 'Create admin' : 'Sign in' ?>
        </button>
        <div class="gf-msg" id="msg"></div>
      </form>
      <p class="gf-faint" style="margin-top:1.25rem">Health: <a style="color:var(--gf-accent)" href="<?= $h('/api/health.php') ?>">/api/health.php</a></p>
    </div>
  </div>
  <script>
    const base = <?= json_encode($base) ?>;
    const needsSetup = <?= $needsSetup ? 'true' : 'false' ?>;
    const msg = document.getElementById('msg');
    document.getElementById('auth-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      msg.className = 'gf-msg'; msg.textContent = '…';
      const body = {
        username: document.getElementById('username').value.trim(),
        password: document.getElementById('password').value,
      };
      const dn = document.getElementById('display_name');
      if (dn) body.display_name = dn.value.trim();
      const path = needsSetup ? '/api/setup.php' : '/api/login.php';
      const res = await fetch(base + path, {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      });
      const data = await res.json().catch(() => ({}));
      if (!data.ok) {
        msg.className = 'gf-msg err';
        msg.textContent = data.error || ('HTTP ' + res.status);
        return;
      }
      msg.className = 'gf-msg ok';
      msg.textContent = 'OK — loading dashboard…';
      location.reload();
    });
  </script>
<?php else: ?>
  <div class="gf-app">
    <header class="gf-header">
      <div class="gf-header-inner">
        <div class="gf-logo">
          <img src="<?= $h('/assets/grokify-icon.png') ?>" alt="" width="36" height="36" onerror="this.parentElement.textContent='G'">
        </div>
        <div class="gf-title">
          <h1><?= htmlspecialchars($appName, ENT_QUOTES) ?></h1>
          <p><?= htmlspecialchars($displayName, ENT_QUOTES) ?><?= $role ? ' · ' . htmlspecialchars($role, ENT_QUOTES) : '' ?></p>
        </div>
        <span class="gf-badge" id="gf-bridge-badge">Bridge…</span>
        <button type="button" class="gf-btn gf-btn-inline" id="btn-logout">Sign out</button>
      </div>
    </header>

    <nav class="gf-nav" aria-label="GrokifyOS">
      <button type="button" class="active" data-goto="home">Home</button>
      <button type="button" data-goto="chat">Chat</button>
      <button type="button" data-goto="devices">Devices</button>
      <button type="button" data-goto="build">Build</button>
    </nav>

    <div class="gf-shell gf-main">
    <section class="gf-panel active" id="panel-home" data-panel="home">
      <div class="gf-stat-grid">
        <div class="gf-stat">
          <div class="n" id="stat-devices"><?= count($activeDevices) ?></div>
          <div class="l">Devices</div>
        </div>
        <div class="gf-stat">
          <div class="n" id="stat-apk"><?= $latestApk ? htmlspecialchars((string) $latestApk['version_name'], ENT_QUOTES) : '—' ?></div>
          <div class="l">Latest APK</div>
        </div>
        <div class="gf-stat">
          <div class="n" id="stat-model">…</div>
          <div class="l">Model</div>
        </div>
        <div class="gf-stat">
          <div class="n" id="stat-bridge">…</div>
          <div class="l">Bridge</div>
        </div>
      </div>

      <div class="gf-home-grid" style="margin-top:0.85rem">
        <div class="gf-card">
          <h2>Install on your phone</h2>
          <?php if ($latestApk): ?>
            <p class="gf-muted">
              Latest: <strong style="color:#fff"><?= htmlspecialchars((string) $latestApk['version_name'], ENT_QUOTES) ?></strong>
              (<?= number_format((int) $latestApk['file_size'] / 1048576, 2) ?> MB)
            </p>
            <a class="gf-btn gf-btn-accent" style="margin-top:0.75rem;text-align:center"
               href="<?= $h('/api/apk-download.php') ?>" download="grokifyos.apk">Download APK</a>
          <?php else: ?>
            <p class="gf-muted">No APK published yet. Publish from the host with <code>android/scripts/publish.sh</code>, then refresh.</p>
          <?php endif; ?>
        </div>

        <div class="gf-card">
          <h2>Quick actions</h2>
          <div class="gf-row">
            <button type="button" class="gf-btn gf-btn-accent" data-goto="chat">Open chat</button>
            <button type="button" class="gf-btn" data-goto="devices">Devices</button>
            <button type="button" class="gf-btn" data-goto="build">Build</button>
          </div>
        </div>

        <div class="gf-card gf-about">
          <h2>About</h2>
          <p class="gf-muted">Production self-host stack: password auth, real MySQL sessions/messages, Grok Build bridge, Android device tokens. Nothing is seeded as demo content.</p>
          <?php if (!$chatReady): ?>
            <p class="gf-badge warn" style="margin-top:0.75rem;display:inline-block">Run schema migrations</p>
          <?php endif; ?>
        </div>
      </div>
    </section>

    <section class="gf-panel" id="panel-chat" data-panel="chat">
      <?php if ($chatReady): ?>
      <div id="sc-root" class="sc-root">
        <div id="sc-bridge-warn" class="sc-bridge-warn hidden">Bridge offline — start the GrokifyOS bridge and set <code>GROKIFY_BRIDGE_URL</code>.</div>
        <div class="sc-topbar">
          <span class="sc-topbar-title" id="sc-topbar-title">New Chat</span>
          <span class="sc-status-dot" id="sc-conn-dot" title="WebSocket"></span>
          <button type="button" class="sc-usage-chip" id="sc-usage-chip" title="Grok Build weekly usage (tap to refresh)">Usage …</button>
          <button type="button" class="sc-toolbar-btn" id="sc-open-log" title="Audit log">Log</button>
        </div>
        <div class="sc-messages" id="sc-messages">
          <div class="sc-welcome" id="sc-welcome">
            <h3>GrokifyOS Chat</h3>
            <p>Sessions and messages are stored in your database. Streaming agents use the configured WebSocket bridge.</p>
          </div>
        </div>
        <div class="sc-toolbar">
          <div class="sc-wrap relative" id="sc-history-wrap">
            <button type="button" class="sc-toolbar-btn" id="sc-history-btn">History</button>
            <div class="sc-popover" id="sc-history-popover">
              <div class="sc-popover-header">
                <span>Sessions</span>
                <button type="button" class="text-xs text-white" id="sc-new-chat">+ New</button>
              </div>
              <div class="sc-popover-body" id="sc-session-list"></div>
            </div>
          </div>
          <button type="button" class="sc-toolbar-btn active" id="sc-context-toggle">Context</button>
          <button type="button" class="sc-toolbar-btn active" id="sc-keep-awake" title="Keep screen on">Screen on</button>
          <div class="sc-wrap relative" id="sc-notes-wrap">
            <button type="button" class="sc-toolbar-btn" id="sc-notes-btn">Notes <span id="sc-notes-badge" class="hidden text-[10px] bg-white/20 px-1 rounded"></span></button>
            <div class="sc-popover" id="sc-notes-popover" style="min-width:280px">
              <div class="sc-popover-header"><span>Instructions (DB)</span></div>
              <div class="sc-popover-body" id="sc-notes-list"></div>
              <div class="p-2 border-t border-[#272b31] flex gap-1">
                <input type="text" id="sc-notes-input" maxlength="500" placeholder="New instruction…" class="flex-1 text-xs bg-[#0f1115] border border-[#272b31] rounded px-2 py-1 text-white">
                <button type="button" id="sc-notes-add" class="text-xs px-2 py-1 bg-white text-[#0f1115] rounded font-semibold">Add</button>
              </div>
            </div>
          </div>
          <div class="sc-toolbar-spacer"></div>
          <div class="sc-wrap relative" id="sc-settings-wrap">
            <button type="button" class="sc-toolbar-btn" id="sc-settings-btn">Settings</button>
            <div class="sc-popover sc-settings-popover" id="sc-settings-popover" style="right:0;left:auto">
              <div class="sc-popover-header">Settings</div>
              <div class="sc-settings-body">
                <div id="sc-usage-detail" class="sc-usage-detail">
                  <div class="sc-usage-detail-head">
                    <div>
                      <div class="sc-usage-detail-title">Weekly usage</div>
                      <span class="sc-usage-detail-tier" id="sc-usage-tier" hidden></span>
                    </div>
                    <button type="button" class="sc-usage-refresh" id="sc-usage-refresh" title="Refresh usage">Refresh</button>
                  </div>
                  <div class="sc-usage-detail-body" id="sc-usage-detail-body">Loading…</div>
                </div>
                <label class="sc-settings-label" for="sc-model-select">Model</label>
                <select id="sc-model-select" class="sc-select w-full max-w-none"></select>

                <div class="sc-settings-section">CHAT</div>
                <label class="sc-setting-row" for="sc-set-history">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Send history / context</span>
                    <span class="sc-setting-sub">Include prior session messages with each prompt</span>
                  </span>
                  <input type="checkbox" id="sc-set-history" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-keep-awake">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Keep screen on</span>
                    <span class="sc-setting-sub">Prevent sleep while chat is open (Wake Lock)</span>
                  </span>
                  <input type="checkbox" id="sc-set-keep-awake" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-enter-newline">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Enter for newline</span>
                    <span class="sc-setting-sub" id="sc-set-enter-hint">Enter inserts a new line; send with the button or Ctrl+Enter</span>
                  </span>
                  <input type="checkbox" id="sc-set-enter-newline" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-show-tools">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Show tools</span>
                    <span class="sc-setting-sub" id="sc-set-tools-hint">Tool call cards appear in the chat transcript</span>
                  </span>
                  <input type="checkbox" id="sc-set-show-tools" class="sc-setting-check" checked>
                </label>
                <label class="sc-setting-row" for="sc-set-show-thoughts">
                  <span class="sc-setting-text">
                    <span class="sc-setting-title">Show thoughts</span>
                    <span class="sc-setting-sub" id="sc-set-thoughts-hint">Thinking / thought cards appear in the chat transcript</span>
                  </span>
                  <input type="checkbox" id="sc-set-show-thoughts" class="sc-setting-check" checked>
                </label>
              </div>
            </div>
          </div>
        </div>
        <div class="sc-input-area">
          <div class="sc-input-wrap">
            <textarea id="sc-prompt" rows="2" placeholder="Message GrokifyOS…"></textarea>
            <button type="button" class="sc-send-btn" id="sc-send-btn" disabled title="Send">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
            </button>
          </div>
        </div>
        <div class="sc-log-panel" id="sc-log-panel">
          <div class="sc-topbar">
            <span class="sc-topbar-title text-sm">Chat audit</span>
            <select id="sc-log-filter-level" class="sc-select">
              <option value="">All levels</option>
              <option value="debug">Debug</option>
              <option value="info">Info</option>
              <option value="warning">Warn</option>
              <option value="error">Error</option>
            </select>
            <select id="sc-log-filter-cat" class="sc-select">
              <option value="">All categories</option>
              <option value="access">Access</option>
              <option value="connection">Connection</option>
              <option value="message">Message</option>
              <option value="agent">Agent</option>
              <option value="process">Process</option>
              <option value="agent_done">Done</option>
              <option value="error">Error</option>
            </select>
            <button type="button" class="sc-toolbar-btn" id="sc-close-log">Close</button>
          </div>
          <div class="sc-log-body" id="sc-log-body"></div>
        </div>
      </div>
      <div class="sc-msg-actions" id="sc-msg-actions" aria-hidden="true">
        <button type="button" class="sc-msg-action-btn" data-action="copy" title="Copy">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
        </button>
        <button type="button" class="sc-msg-action-btn" data-action="exclude" title="Exclude from context">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
        </button>
        <button type="button" class="sc-msg-action-btn" data-action="delete" title="Delete message">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
        </button>
      </div>
      <?php else: ?>
      <div class="gf-card"><p class="gf-muted">Chat tables missing. Run <code>php scripts/install.php</code>.</p></div>
      <?php endif; ?>
    </section>

    <section class="gf-panel" id="panel-devices" data-panel="devices">
      <div class="gf-split">
        <div class="gf-card">
          <h2>Register device</h2>
          <p class="gf-muted" style="margin-bottom:0.75rem">Creates a long-lived API token for the Android app (shown once). Prefix: <code>gos_</code></p>
          <label class="gf-label">Device name</label>
          <input type="text" id="device-name" class="gf-input" placeholder="Pixel / my phone" maxlength="128">
          <button type="button" class="gf-btn gf-btn-primary" id="btn-create-device" style="margin-top:0.75rem">Create device token</button>
          <div id="new-token-wrap" class="hidden" style="margin-top:0.85rem">
            <p class="gf-faint" style="margin-bottom:0.35rem">Copy now — not shown again:</p>
            <div class="gf-token-box" id="new-token"></div>
            <button type="button" class="gf-btn" id="btn-copy-token" style="margin-top:0.5rem">Copy token</button>
          </div>
        </div>
        <div class="gf-card">
          <h2>Your devices</h2>
          <div id="device-list">
            <?php if (!$activeDevices): ?>
              <p class="gf-muted">No active devices yet.</p>
            <?php else: ?>
              <?php foreach ($activeDevices as $d): ?>
              <div class="gf-device" data-id="<?= (int) $d['id'] ?>">
                <div class="gf-device-body">
                  <strong><?= htmlspecialchars((string) $d['device_name'], ENT_QUOTES) ?></strong>
                  <div class="gf-faint">
                    <?= htmlspecialchars((string) $d['token_prefix'], ENT_QUOTES) ?>…
                    <?php if (!empty($d['app_version_name'])): ?>
                      · v<?= htmlspecialchars((string) $d['app_version_name'], ENT_QUOTES) ?>
                    <?php endif; ?>
                    <?php if (!empty($d['last_seen_at'])): ?>
                      · seen <?= htmlspecialchars((string) $d['last_seen_at'], ENT_QUOTES) ?>
                    <?php endif; ?>
                  </div>
                </div>
                <button type="button" class="gf-btn gf-btn-danger btn-revoke" data-id="<?= (int) $d['id'] ?>">Revoke</button>
              </div>
              <?php endforeach; ?>
            <?php endif; ?>
          </div>
        </div>
      </div>
    </section>

    <section class="gf-panel" id="panel-build" data-panel="build">
      <div class="gf-split">
        <div class="gf-card">
          <h2>Latest release</h2>
          <?php if ($latestApk): ?>
            <p class="gf-muted">
              <strong style="color:#fff"><?= htmlspecialchars((string) $latestApk['version_name'], ENT_QUOTES) ?></strong>
              (code <?= (int) $latestApk['version_code'] ?>)
              · <?= number_format((int) $latestApk['file_size'] / 1048576, 2) ?> MB
            </p>
            <?php if (!empty($latestApk['changelog'])): ?>
              <p class="gf-faint" style="margin-top:0.5rem;white-space:pre-wrap"><?= htmlspecialchars((string) $latestApk['changelog'], ENT_QUOTES) ?></p>
            <?php endif; ?>
            <a class="gf-btn gf-btn-accent" style="margin-top:0.85rem;text-align:center"
               href="<?= $h('/api/apk-download.php') ?>" download="grokifyos.apk">Download APK</a>
          <?php else: ?>
            <p class="gf-muted">No APK published yet. Ship one from the host with the publish script.</p>
          <?php endif; ?>
        </div>
        <div class="gf-card">
          <h2>Publish from host</h2>
          <p class="gf-muted">APK releases are registered on the server with the CLI — not uploaded from this dashboard.</p>
          <pre class="gf-code">cd android
./scripts/publish.sh debug --changelog "What changed"</pre>
          <p class="gf-faint" style="margin-top:0.75rem">Requires increasing <code>versionCode</code>. Details: <code>android/README.md</code>.</p>
        </div>
      </div>
    </section>
    </div><!-- .gf-shell -->
  </div><!-- .gf-app -->

  <script>
    window.API_BASE = <?= json_encode($base . '/api') ?>;
  </script>
  <script src="<?= $h('/assets/vendor/marked/marked.min.js') ?>"></script>
  <script src="<?= $h('/assets/system-chat.js') ?>?v=<?= $assetV ?>"></script>
  <script>
  (function () {
    const base = <?= json_encode($base) ?>;
    const $ = (id) => document.getElementById(id);

    function showPanel(name) {
      document.querySelectorAll('.gf-panel').forEach((p) => p.classList.toggle('active', p.dataset.panel === name));
      document.querySelectorAll('.gf-nav button').forEach((b) => b.classList.toggle('active', b.dataset.goto === name));
      if (name === 'chat' && typeof systemChatInit === 'function') systemChatInit();
      if (name !== 'chat' && typeof systemChatOnTabLeave === 'function') systemChatOnTabLeave();
      try { history.replaceState(null, '', '#' + name); } catch (_) {}
    }

    document.querySelectorAll('[data-goto]').forEach((el) => {
      el.addEventListener('click', () => showPanel(el.dataset.goto));
    });

    $('btn-logout')?.addEventListener('click', async () => {
      await fetch(base + '/api/logout.php', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}' });
      location.reload();
    });

    $('btn-create-device')?.addEventListener('click', async () => {
      const name = ($('device-name')?.value || 'Android').trim() || 'Android';
      const res = await fetch(base + '/api/devices.php', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ device_name: name }),
      });
      const data = await res.json().catch(() => ({}));
      if (!data.ok) { alert(data.error || 'failed'); return; }
      $('new-token').textContent = data.token;
      $('new-token-wrap').classList.remove('hidden');
      const list = $('device-list');
      if (list && data.device) {
        const empty = list.querySelector('.gf-muted');
        if (empty) empty.remove();
        const row = document.createElement('div');
        row.className = 'gf-device';
        row.dataset.id = data.device.id;
        row.innerHTML = '<div class="gf-device-body"><strong></strong><div class="gf-faint"></div></div>' +
          '<button type="button" class="gf-btn gf-btn-danger btn-revoke" data-id="' + data.device.id + '">Revoke</button>';
        row.querySelector('strong').textContent = data.device.device_name;
        row.querySelector('.gf-faint').textContent = data.device.token_prefix + '…';
        list.prepend(row);
        bindRevoke(row.querySelector('.btn-revoke'));
        const n = $('stat-devices');
        if (n) n.textContent = String((parseInt(n.textContent, 10) || 0) + 1);
      }
    });

    $('btn-copy-token')?.addEventListener('click', async () => {
      const t = $('new-token')?.textContent || '';
      try { await navigator.clipboard.writeText(t); } catch (_) {}
    });

    function bindRevoke(btn) {
      if (!btn || btn.dataset.bound) return;
      btn.dataset.bound = '1';
      btn.addEventListener('click', async () => {
        const id = btn.dataset.id;
        if (!confirm('Revoke this device token?')) return;
        const res = await fetch(base + '/api/devices.php?id=' + encodeURIComponent(id), {
          method: 'DELETE', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ id: Number(id) }),
        });
        const data = await res.json().catch(() => ({}));
        if (data.ok) {
          btn.closest('.gf-device')?.remove();
          const n = $('stat-devices');
          if (n) n.textContent = String(Math.max(0, (parseInt(n.textContent, 10) || 1) - 1));
        }
      });
    }
    document.querySelectorAll('.btn-revoke').forEach(bindRevoke);

    async function refreshHomeStats() {
      try {
        const res = await fetch(base + '/api/admin-system-chat-models.php', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
        const data = await res.json().catch(() => ({}));
        if (data.ok) {
          const sel = (data.selected || data.default_model || '').replace(/^gb:/, '');
          if ($('stat-model')) $('stat-model').textContent = sel || '—';
          const ok = data.bridge_healthy !== false;
          if ($('stat-bridge')) $('stat-bridge').textContent = ok ? 'OK' : 'Down';
          const badge = $('gf-bridge-badge');
          if (badge) {
            badge.textContent = ok ? 'Bridge OK' : 'Bridge down';
            badge.classList.toggle('ok', ok);
            badge.classList.toggle('warn', !ok);
          }
        }
      } catch (_) {
        if ($('stat-bridge')) $('stat-bridge').textContent = '?';
      }
    }
    refreshHomeStats();

    const hash = (location.hash || '').replace(/^#/, '');
    if (hash && document.getElementById('panel-' + hash)) showPanel(hash);
  })();
  </script>
<?php endif; ?>
</body>
</html>
