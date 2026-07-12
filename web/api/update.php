<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$access = gos_require_access();
$currentCode = (int) ($_GET['version_code'] ?? $_GET['versionCode'] ?? 0);
$latest = gos_latest_apk();
$site = rtrim(gos_site_url(), '/');

if (!empty($access['device'])) {
    $vName = isset($_GET['version_name']) ? (string) $_GET['version_name'] : null;
    gos_touch_device((int) $access['device']['id'], $vName, $currentCode > 0 ? $currentCode : null);
}

if ($latest === null) {
    gos_api_json([
        'ok' => true,
        'update_available' => false,
        'latest' => null,
    ]);
}

$latestCode = (int) $latest['version_code'];
$updateAvailable = $currentCode > 0 ? $latestCode > $currentCode : true;

gos_api_json([
    'ok' => true,
    'update_available' => $updateAvailable,
    'current_version_code' => $currentCode,
    'latest' => [
        'version_code' => $latestCode,
        'version_name' => $latest['version_name'],
        'file_size' => (int) ($latest['file_size'] ?? 0),
        'sha256' => $latest['sha256'] ?? null,
        'changelog' => $latest['changelog'] ?? null,
        'min_sdk' => isset($latest['min_sdk']) && $latest['min_sdk'] !== null ? (int) $latest['min_sdk'] : null,
        'download_url' => $site . '/api/apk-download.php',
        'created_at' => $latest['created_at'] ?? null,
    ],
]);
