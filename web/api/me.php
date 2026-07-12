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
$site = rtrim(gos_site_url(), '/');
$wsPath = gos_system_chat_ws_path();
// Build wss URL from site
$wsUrl = preg_replace('#^https:#', 'wss:', $site) . $wsPath;
if (str_starts_with($site, 'http://')) {
    $wsUrl = preg_replace('#^http:#', 'ws:', $site) . $wsPath;
}

if ($user === null) {
    gos_api_json([
        'ok' => true,
        'authenticated' => false,
        'needs_setup' => gos_needs_setup(),
        'app' => $settings['app_name'] ?? 'GrokifyOS',
        'auth' => 'password',
        'site_url' => $site,
        'ws_path' => $wsPath,
        'ws_url' => $wsUrl,
    ]);
}

$device = $bearer['device'] ?? null;
$latest = gos_latest_apk();

gos_api_json([
    'ok' => true,
    'authenticated' => true,
    'needs_setup' => false,
    'user' => gos_public_user($user),
    'auth' => $bearer !== null ? 'token' : 'session',
    'app' => $settings['app_name'] ?? 'GrokifyOS',
    'site_url' => $site,
    'device' => $device ? [
        'id' => (int) $device['id'],
        'name' => $device['device_name'],
        'token_prefix' => $device['token_prefix'],
        'app_version_name' => $device['app_version_name'] ?? null,
        'app_version_code' => isset($device['app_version_code']) ? (int) $device['app_version_code'] : null,
        'last_seen_at' => $device['last_seen_at'] ?? null,
    ] : null,
    'ws_token' => gos_system_chat_ws_token($user),
    'ws_path' => $wsPath,
    'ws_url' => $wsUrl,
    'bridge_url_hint' => gos_system_chat_bridge_url(),
    'latest_apk' => $latest ? [
        'version_code' => (int) $latest['version_code'],
        'version_name' => $latest['version_name'],
        'file_size' => (int) ($latest['file_size'] ?? 0),
        'sha256' => $latest['sha256'] ?? null,
        'changelog' => $latest['changelog'] ?? null,
        'download_url' => $site . '/api/apk-download.php',
        'created_at' => $latest['created_at'] ?? null,
    ] : null,
    'site' => [
        'name' => $settings['app_name'] ?? 'GrokifyOS',
        'origin' => $site,
    ],
]);
