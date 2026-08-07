<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$access = gos_require_access();
$user = $access['user'];
$site = rtrim(gos_site_url(), '/');
$wsPath = gos_system_chat_ws_path();
$wsUrl = (str_starts_with($site, 'https:') ? preg_replace('#^https:#', 'wss:', $site) : preg_replace('#^http:#', 'ws:', $site)) . $wsPath;

$bridgeBase = rtrim(gos_system_chat_bridge_url(), '/');
$ctx = stream_context_create(['http' => ['timeout' => 3, 'method' => 'GET', 'ignore_errors' => true]]);
$healthRaw = @file_get_contents($bridgeBase . '/health', false, $ctx);
$health = is_string($healthRaw) ? json_decode($healthRaw, true) : null;
$bridgeHealthy = is_array($health) && in_array(($health['status'] ?? ''), ['healthy', 'degraded'], true)
    ? ($health['status'] === 'healthy' || !empty(array_filter($health['backends'] ?? [], static fn ($b) => !empty($b['ok']))))
    : false;
if (!$bridgeHealthy) {
    $modelsRaw = @file_get_contents($bridgeBase . '/models', false, $ctx);
    $bridgeHealthy = $modelsRaw !== false;
}

$selected = gos_system_chat_selected_model();
$latest = gos_latest_apk('phone');
$latestWear = gos_latest_apk('wear');
$devices = $access['auth'] === 'session' ? gos_devices_for_user((int) $user['id'])['active'] : [];

gos_api_json([
    'ok' => true,
    'bridge_healthy' => $bridgeHealthy,
    'bridge_ha' => is_array($health) ? [
        'role' => $health['role'] ?? null,
        'status' => $health['status'] ?? null,
        'backends' => $health['backends'] ?? [],
    ] : null,
    'selected_model' => $selected,
    'ws_token' => gos_system_chat_ws_token($user),
    'ws_path' => $wsPath,
    'ws_url' => $wsUrl,
    'device_count' => count($devices),
    'latest_apk' => $latest ? [
        'channel' => 'phone',
        'version_code' => (int) $latest['version_code'],
        'version_name' => $latest['version_name'],
        'created_at' => $latest['created_at'] ?? null,
    ] : null,
    'latest_wear_apk' => $latestWear ? [
        'channel' => 'wear',
        'version_code' => (int) $latestWear['version_code'],
        'version_name' => $latestWear['version_name'],
        'created_at' => $latestWear['created_at'] ?? null,
    ] : null,
    'tables_ready' => gos_table_exists('grokify_devices'),
    'system_chat_ready' => gos_system_chat_tables_ready(),
]);
