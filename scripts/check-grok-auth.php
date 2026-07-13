#!/usr/bin/env php
<?php

declare(strict_types=1);

/**
 * Check Grok Build CLI auth status (auth.json + optional live probe).
 *
 * Usage:
 *   php scripts/check-grok-auth.php
 *   php scripts/check-grok-auth.php --json
 *   php scripts/check-grok-auth.php --probe          # also run a 1-token grok call
 *   php scripts/check-grok-auth.php --refresh        # force token refresh via OIDC
 *
 * Exit codes:
 *   0 = signed in / usable
 *   1 = missing / expired / refresh failed
 *   2 = probe failed
 */

$root = dirname(__DIR__);
require_once $root . '/web/includes/paths.php';
require_once $root . '/web/includes/settings.php';
require_once $root . '/web/includes/system-chat.php';

$args = array_slice($argv, 1);
$asJson = in_array('--json', $args, true);
$doProbe = in_array('--probe', $args, true);
$forceRefresh = in_array('--refresh', $args, true);

$loaded = gos_grok_auth_load();
$result = [
    'ok' => false,
    'path' => null,
    'email' => null,
    'expires_at' => null,
    'expired' => null,
    'has_refresh' => false,
    'token_present' => false,
    'message' => '',
    'probe' => null,
];

if ($loaded === null) {
    $result['message'] = 'No auth.json found. Run: grok login --device-code'
        . '  (or set GROKIFY_GROK_AUTH_JSON)';
    $result['candidates'] = gos_grok_auth_json_candidates();
    emit_result($result, $asJson, 1);
}

$entry = $loaded['entry'];
$result['path'] = $loaded['path'] ?? null;
$result['email'] = $entry['email'] ?? null;
$result['expires_at'] = $entry['expires_at'] ?? null;
$result['has_refresh'] = !empty($entry['refresh_token']);
$token = (string) ($entry['key'] ?? $entry['access_token'] ?? '');
$result['token_present'] = $token !== '';
$result['expired'] = $token !== '' && gos_grok_auth_token_expired($entry);

if ($forceRefresh || $result['expired']) {
    $ensured = gos_grok_auth_ensure_token();
    if (!empty($ensured['ok'])) {
        $result['ok'] = true;
        $result['message'] = $result['expired'] || $forceRefresh
            ? 'Token refreshed successfully'
            : 'Auth OK';
        $result['expired'] = false;
        if (!empty($ensured['entry']['expires_at'])) {
            $result['expires_at'] = $ensured['entry']['expires_at'];
        }
        if (!empty($ensured['entry']['email'])) {
            $result['email'] = $ensured['entry']['email'];
        }
    } else {
        $result['ok'] = false;
        $result['error'] = $ensured['error'] ?? 'auth_failed';
        $result['message'] = $ensured['message']
            ?? 'Auth refresh failed — run: grok login --device-code';
        if (isset($ensured['http_code'])) {
            $result['http_code'] = $ensured['http_code'];
        }
        emit_result($result, $asJson, 1);
    }
} else {
    $result['ok'] = $result['token_present'];
    $result['message'] = $result['ok']
        ? 'Auth OK (token not expired)'
        : 'Auth file present but no access token — run: grok login --device-code';
    if (!$result['ok']) {
        emit_result($result, $asJson, 1);
    }
}

if ($doProbe) {
    $bin = getenv('GROKIFY_GROK_BIN') ?: (getenv('GROKPOT_GROK_BIN') ?: '/root/.grok/bin/grok');
    if (!is_executable($bin)) {
        $result['probe'] = ['ok' => false, 'error' => 'grok_bin_missing', 'path' => $bin];
        $result['message'] .= ' · probe skipped (no grok binary)';
        emit_result($result, $asJson, 0);
    }
    $cmd = escapeshellarg($bin)
        . ' --output-format json -p '
        . escapeshellarg('reply with only: pong')
        . ' 2>&1';
    $out = [];
    $code = 0;
    exec($cmd, $out, $code);
    $text = implode("\n", $out);
    $probeOk = $code === 0 && stripos($text, 'not signed in') === false;
    // Prefer JSON text field when present
    if ($probeOk && preg_match('/\{[\s\S]*"text"\s*:\s*"([^"]*)"/', $text, $m)) {
        $probeOk = true;
    }
    if (!$probeOk && isAuthish($text)) {
        $result['ok'] = false;
        $result['probe'] = [
            'ok' => false,
            'exit_code' => $code,
            'detail' => truncate($text, 500),
            'error' => 'auth_required',
        ];
        $result['message'] = 'Live probe: Grok CLI is not signed in — run: grok login --device-code';
        emit_result($result, $asJson, 2);
    }
    $result['probe'] = [
        'ok' => $probeOk,
        'exit_code' => $code,
        'detail' => truncate($text, 300),
    ];
    if (!$probeOk) {
        $result['ok'] = false;
        $result['message'] = 'Live probe failed (exit ' . $code . ')';
        emit_result($result, $asJson, 2);
    }
    $result['message'] .= ' · live probe OK';
}

emit_result($result, $asJson, 0);

// ── helpers ──────────────────────────────────────────────────────────

function isAuthish(string $text): bool
{
    return (bool) preg_match(
        '/not signed in|authentication|auth(?:entication)? failed|please (?:log|sign) in|grok login|unauthorized/i',
        $text
    );
}

function truncate(string $s, int $n): string
{
    $s = trim($s);
    if (strlen($s) <= $n) {
        return $s;
    }

    return substr($s, 0, $n - 1) . '…';
}

/** @param array<string, mixed> $result */
function emit_result(array $result, bool $asJson, int $exit): void
{
    if ($asJson) {
        echo json_encode($result, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT) . "\n";
        exit($exit);
    }

    $icon = !empty($result['ok']) ? 'OK' : 'FAIL';
    echo "Grok Build auth: [{$icon}]\n";
    if (!empty($result['path'])) {
        echo "  path:        {$result['path']}\n";
    }
    if (!empty($result['email'])) {
        echo "  email:       {$result['email']}\n";
    }
    if (array_key_exists('expires_at', $result) && $result['expires_at'] !== null) {
        $flag = !empty($result['expired']) ? ' (EXPIRED)' : '';
        echo "  expires_at:  {$result['expires_at']}{$flag}\n";
    }
    echo '  has_refresh: ' . (!empty($result['has_refresh']) ? 'yes' : 'no') . "\n";
    if (!empty($result['message'])) {
        echo "  message:     {$result['message']}\n";
    }
    if (!empty($result['error'])) {
        echo "  error:       {$result['error']}\n";
    }
    if (isset($result['probe']) && is_array($result['probe'])) {
        $p = $result['probe'];
        echo '  probe:       ' . (!empty($p['ok']) ? 'OK' : 'FAIL')
            . (isset($p['exit_code']) ? " (exit {$p['exit_code']})" : '') . "\n";
        if (!empty($p['detail'])) {
            echo "  probe_out:   " . str_replace("\n", ' ', (string) $p['detail']) . "\n";
        }
    }
    if (!empty($result['candidates']) && is_array($result['candidates'])) {
        echo "  looked in:\n";
        foreach ($result['candidates'] as $c) {
            echo "    - {$c}\n";
        }
    }
    if (empty($result['ok'])) {
        echo "\nFix:\n  grok login --device-code\n";
        echo "  # ensure GROKIFY_GROK_AUTH_JSON points at ~/.grok/auth.json\n";
    }
    exit($exit);
}
