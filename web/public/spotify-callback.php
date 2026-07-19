<?php
/**
 * Spotify OAuth bounce → GrokifyOS Android app.
 * Register: https://<host>/spotify-callback.php
 */
declare(strict_types=1);

if (function_exists('header_remove')) {
    @header_remove('Content-Security-Policy');
}
header(
    "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; " .
    "style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"
);
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Pragma: no-cache');

$code = isset($_GET['code']) ? (string) $_GET['code'] : '';
$state = isset($_GET['state']) ? (string) $_GET['state'] : '';
$error = isset($_GET['error']) ? (string) $_GET['error'] : '';
$errorDesc = isset($_GET['error_description']) ? (string) $_GET['error_description'] : '';

$params = [];
if ($error !== '') {
    $params['error'] = $error;
    if ($errorDesc !== '') {
        $params['error_description'] = $errorDesc;
    }
} else {
    if ($code !== '') {
        $params['code'] = $code;
    }
    if ($state !== '') {
        $params['state'] = $state;
    }
}

$query = http_build_query($params, '', '&', PHP_QUERY_RFC3986);
$deep = 'grokifyos://spotify-callback' . ($query !== '' ? ('?' . $query) : '');

$intentBody = 'spotify-callback' . ($query !== '' ? ('?' . $query) : '');
$intentDebug = 'intent://' . $intentBody
    . '#Intent;scheme=grokifyos;package=io.grokify.os.debug;S.browser_fallback_url='
    . rawurlencode($deep) . ';end';
$intentRelease = 'intent://' . $intentBody
    . '#Intent;scheme=grokifyos;package=io.grokify.os;S.browser_fallback_url='
    . rawurlencode($deep) . ';end';

$hasCode = $code !== '';
$hasError = $error !== '';
$title = $hasError ? 'Auth failed' : ($hasCode ? 'Opening…' : 'No code');
$subtitle = $hasError
    ? htmlspecialchars($error . ($errorDesc !== '' ? (': ' . $errorDesc) : ''), ENT_QUOTES, 'UTF-8')
    : ($hasCode ? 'Return to GrokifyOS' : 'Start Connect from the Spotify app');

$accept = $_SERVER['HTTP_ACCEPT'] ?? '';
$wantsHtml = stripos($accept, 'text/html') !== false || $accept === '' || $accept === '*/*';
$ua = $_SERVER['HTTP_USER_AGENT'] ?? '';
$isAndroid = stripos($ua, 'Android') !== false;

// Prefer App Link path: non-HTML Android clients get an immediate intent redirect.
if (!$wantsHtml && $isAndroid && ($hasCode || $hasError)) {
    header('Location: ' . $intentDebug, true, 302);
    exit;
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="theme-color" content="#05070b" />
  <title><?= htmlspecialchars($title, ENT_QUOTES, 'UTF-8') ?></title>
  <style>
    :root { color-scheme: dark; }
    * { box-sizing: border-box; }
    body {
      margin: 0; min-height: 100vh; display: grid; place-items: center;
      font-family: Inter, system-ui, -apple-system, sans-serif;
      background:
        radial-gradient(600px 320px at 20% 0%, rgba(29,185,84,.18), transparent 55%),
        radial-gradient(500px 280px at 90% 10%, rgba(62,224,255,.1), transparent 50%),
        #05070b;
      color: #e6edf5; padding: 24px; text-align: center;
    }
    .card {
      max-width: 20rem; width: 100%; padding: 1.4rem 1.25rem 1.25rem;
      border-radius: 16px; border: 1px solid rgba(120,160,200,.14);
      background: linear-gradient(165deg, #121a22, #0c1218);
      box-shadow: 0 16px 48px rgba(0,0,0,.5), 0 0 40px rgba(29,185,84,.08);
    }
    .pulse {
      width: 42px; height: 42px; margin: 0 auto 12px; border-radius: 12px;
      background: linear-gradient(145deg, #1ed760, #169c46);
      box-shadow: 0 0 24px rgba(29,185,84,.45);
      display: grid; place-items: center; font-size: 1.15rem;
      animation: p 1.6s ease-in-out infinite;
    }
    @keyframes p {
      0%, 100% { transform: scale(1); box-shadow: 0 0 18px rgba(29,185,84,.35); }
      50% { transform: scale(1.05); box-shadow: 0 0 32px rgba(29,185,84,.55); }
    }
    h1 { font-size: 1.05rem; margin: 0 0 .35rem; font-weight: 700; letter-spacing: -.02em; }
    p { margin: 0 0 1rem; color: #8b9bb0; font-size: .88rem; line-height: 1.4; }
    .btn {
      display: block; width: 100%; box-sizing: border-box;
      margin: .45rem 0 0; padding: .85rem 1rem; border-radius: 12px;
      font-weight: 700; font-size: .95rem; text-decoration: none; border: none;
      cursor: pointer;
    }
    .btn-primary { background: linear-gradient(135deg, #1ed760, #1db954); color: #04140a; }
    .btn-secondary {
      background: rgba(62,224,255,.1); color: #3ee0ff;
      border: 1px solid rgba(62,224,255,.3);
    }
    .btn-ghost { background: transparent; color: #5c6b7e; font-size: .8rem; font-weight: 600; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .68rem; color: #5c6b7e; margin-top: .85rem; word-break: break-all; }
  </style>
</head>
<body>
  <div class="card">
    <div class="pulse">♪</div>
    <h1><?= htmlspecialchars($title, ENT_QUOTES, 'UTF-8') ?></h1>
    <p><?= $subtitle ?></p>
    <?php if ($hasCode || $hasError): ?>
    <a class="btn btn-primary" id="btnOpen" href="<?= htmlspecialchars($intentDebug, ENT_QUOTES, 'UTF-8') ?>">Open GrokifyOS</a>
    <a class="btn btn-secondary" href="<?= htmlspecialchars($intentRelease, ENT_QUOTES, 'UTF-8') ?>">Release build</a>
    <a class="btn btn-ghost" href="<?= htmlspecialchars($deep, ENT_QUOTES, 'UTF-8') ?>">grokifyos://</a>
    <?php endif; ?>
    <p class="mono">spotify → app</p>
  </div>
  <script>
    (function () {
      var hasPayload = <?= ($hasCode || $hasError) ? 'true' : 'false' ?>;
      if (!hasPayload) return;
      var targets = [
        <?= json_encode($intentDebug, JSON_UNESCAPED_SLASHES) ?>,
        <?= json_encode($deep, JSON_UNESCAPED_SLASHES) ?>,
        <?= json_encode($intentRelease, JSON_UNESCAPED_SLASHES) ?>
      ];
      var i = 0;
      function go() {
        if (i >= targets.length) return;
        var u = targets[i++];
        try { window.location.href = u; } catch (e) {}
        setTimeout(go, 550);
      }
      setTimeout(go, 40);
      // User-gesture fallback for Chrome blocks
      document.getElementById('btnOpen')?.addEventListener('click', function () {
        try { window.location.href = targets[0]; } catch (e) {}
      });
    })();
  </script>
</body>
</html>
