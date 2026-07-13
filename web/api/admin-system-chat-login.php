<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$userId = (int) $access['user']['id'];
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method !== 'GET' && $method !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$force = isset($_GET['force']) && (string) $_GET['force'] !== '0' && (string) $_GET['force'] !== '';
if ($method === 'POST') {
    $body = gos_json_body();
    if (!empty($body['force'])) {
        $force = true;
    }
}

// POST always starts (optionally force-new). GET is status; ?start=1 also starts.
$start = $method === 'POST'
    || (isset($_GET['start']) && (string) $_GET['start'] !== '0' && (string) $_GET['start'] !== '');

if ($start) {
    $login = gos_grok_auth_bridge_login('start', $force);
    gos_system_chat_audit('info', 'auth', 'Grok device login started', [
        'status' => $login['status'] ?? null,
        'user_code' => $login['user_code'] ?? null,
        'force' => $force,
    ], $userId);
} else {
    $login = gos_grok_auth_bridge_login('status', false);
}

// After complete, force-sync so PHP usage can read the new tokens immediately.
if (($login['status'] ?? '') === 'complete') {
    $sync = gos_grok_auth_request_bridge_sync(true);
    $login['auth_sync'] = $sync;
}

gos_api_json([
    'ok' => !empty($login['ok']) || (($login['status'] ?? '') === 'pending') || (($login['status'] ?? '') === 'complete'),
    'login' => $login,
    'login_url' => $login['verification_uri_complete'] ?? null,
    'login_user_code' => $login['user_code'] ?? null,
]);
