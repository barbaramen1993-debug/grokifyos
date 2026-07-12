<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

// Allow unauthenticated status for login UI
if (session_status() !== PHP_SESSION_ACTIVE) {
    gos_session_start();
}

$bearer = gos_auth_from_bearer();
$user = $bearer['user'] ?? gos_current_user();
$settings = require dirname(__DIR__) . '/includes/settings.php';

if ($user === null) {
    gos_api_json([
        'ok' => true,
        'authenticated' => false,
        'needs_setup' => gos_needs_setup(),
        'app' => $settings['app_name'] ?? 'GrokifyOS',
        'auth' => 'password',
    ]);
}

gos_api_json([
    'ok' => true,
    'authenticated' => true,
    'needs_setup' => false,
    'user' => gos_public_user($user),
    'auth' => $bearer !== null ? 'token' : 'session',
    'app' => $settings['app_name'] ?? 'GrokifyOS',
    'site_url' => gos_site_url(),
]);
