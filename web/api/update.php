<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

$access = gos_require_access();
$currentCode = (int) ($_GET['version_code'] ?? $_GET['versionCode'] ?? 0);
$channel = gos_apk_channel(isset($_GET['channel']) ? (string) $_GET['channel'] : 'phone');
$latest = gos_latest_apk($channel);
$site = rtrim(gos_site_url(), '/');

// Only touch phone device metadata when checking the phone channel
if ($channel === 'phone' && !empty($access['device'])) {
    $vName = isset($_GET['version_name']) ? (string) $_GET['version_name'] : null;
    gos_touch_device((int) $access['device']['id'], $vName, $currentCode > 0 ? $currentCode : null);
}

if ($latest === null) {
    gos_api_json([
        'ok' => true,
        'channel' => $channel,
        'update_available' => false,
        'latest' => null,
    ]);
}

$latestCode = (int) $latest['version_code'];
$updateAvailable = $currentCode > 0 ? $latestCode > $currentCode : true;
$summary = gos_apk_public_summary($latest, $site);

gos_api_json([
    'ok' => true,
    'channel' => $channel,
    'update_available' => $updateAvailable,
    'current_version_code' => $currentCode,
    'latest' => $summary,
]);
