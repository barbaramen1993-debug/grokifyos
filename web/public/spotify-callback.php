<?php
/**
 * Spotify OAuth redirect bounce → GrokifyOS Android app.
 *
 * Register this exact URL in the Spotify Developer Dashboard:
 *   https://<your-host>/spotify-callback.php
 *
 * Flow: Spotify → this HTTPS page → App Link / intent:// / custom scheme.
 * Chrome often blocks automatic grokifyos:// navigation; intent:// + package
 * and verified App Links are the reliable paths.
 */
declare(strict_types=1);

// Loosen CSP for this bounce page so browsers allow script + deep-link navigation.
// Apache also sets CSP globally; we unset when possible.
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

// Android Intent URLs — force open by package (debug APK uses .debug suffix).
$intentBody = 'spotify-callback' . ($query !== '' ? ('?' . $query) : '');
$intentDebug = 'intent://' . $intentBody
    . '#Intent;scheme=grokifyos;package=io.grokify.os.debug;end';
$intentRelease = 'intent://' . $intentBody
    . '#Intent;scheme=grokifyos;package=io.grokify.os;end';

$hasCode = $code !== '';
$hasError = $error !== '';
$title = $hasError ? 'Spotify login failed' : ($hasCode ? 'Returning to GrokifyOS…' : 'Spotify callback');
$subtitle = $hasError
    ? htmlspecialchars($error . ($errorDesc !== '' ? (': ' . $errorDesc) : ''), ENT_QUOTES, 'UTF-8')
    : ($hasCode
        ? 'Tap the button below if the app does not open automatically.'
        : 'No authorization code in this URL. Start login from the GrokifyOS Spotify DJ plugin.');

// Prefer 302 to intent:// for non-HTML clients; browsers get the HTML with buttons.
$accept = $_SERVER['HTTP_ACCEPT'] ?? '';
$wantsHtml = stripos($accept, 'text/html') !== false || $accept === '' || $accept === '*/*';
$ua = $_SERVER['HTTP_USER_AGENT'] ?? '';
$isAndroid = stripos($ua, 'Android') !== false;

if (!$wantsHtml && $isAndroid) {
    header('Location: ' . $intentDebug, true, 302);
    exit;
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars($title, ENT_QUOTES, 'UTF-8') ?></title>
  <style>
    body {
      margin: 0; min-height: 100vh; display: grid; place-items: center;
      font-family: system-ui, -apple-system, sans-serif; background: #0a0f14; color: #e8eef6;
      padding: 24px; text-align: center;
    }
    .card {
      max-width: 22rem; width: 100%; padding: 1.5rem; border-radius: 16px;
      border: 1px solid rgba(148,163,184,.25); background: #121a22;
      box-shadow: 0 12px 40px rgba(0,0,0,.45);
    }
    h1 { font-size: 1.15rem; margin: 0 0 .5rem; font-weight: 700; }
    p { margin: 0 0 1rem; color: #94a3b8; font-size: .95rem; line-height: 1.45; }
    .btn {
      display: block; width: 100%; box-sizing: border-box;
      margin: .55rem 0 0; padding: .9rem 1rem; border-radius: 12px;
      font-weight: 700; font-size: 1rem; text-decoration: none; border: none;
      cursor: pointer;
    }
    .btn-primary { background: #1db954; color: #04140a; }
    .btn-secondary { background: rgba(62,224,255,.12); color: #3ee0ff; border: 1px solid rgba(62,224,255,.35); }
    .btn-ghost { background: transparent; color: #94a3b8; font-size: .85rem; font-weight: 600; }
    .hint { font-size: .8rem; color: #64748b; margin-top: 1rem; }
  </style>
</head>
<body>
  <div class="card">
    <h1><?= htmlspecialchars($title, ENT_QUOTES, 'UTF-8') ?></h1>
    <p><?= $subtitle ?></p>
    <?php if ($hasCode || $hasError): ?>
    <a class="btn btn-primary" id="btnOpen" href="<?= htmlspecialchars($intentDebug, ENT_QUOTES, 'UTF-8') ?>">
      Open GrokifyOS
    </a>
    <a class="btn btn-secondary" href="<?= htmlspecialchars($intentRelease, ENT_QUOTES, 'UTF-8') ?>">
      Open release build
    </a>
    <a class="btn btn-ghost" href="<?= htmlspecialchars($deep, ENT_QUOTES, 'UTF-8') ?>">
      Try custom link (grokifyos://)
    </a>
    <?php endif; ?>
    <p class="hint">After the app opens, return to Spotify DJ and tap Refresh.</p>
  </div>
  <script>
    (function () {
      var hasPayload = <?= ($hasCode || $hasError) ? 'true' : 'false' ?>;
      if (!hasPayload) return;
      var targets = [
        <?= json_encode($intentDebug, JSON_UNESCAPED_SLASHES) ?>,
        <?= json_encode($intentRelease, JSON_UNESCAPED_SLASHES) ?>,
        <?= json_encode($deep, JSON_UNESCAPED_SLASHES) ?>
      ];
      var i = 0;
      function go() {
        if (i >= targets.length) return;
        var u = targets[i++];
        try { window.location.href = u; } catch (e) {}
        setTimeout(go, 700);
      }
      // User-gesture-friendly: auto-try once, then rely on the button.
      setTimeout(go, 50);
    })();
  </script>
</body>
</html>
