<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$settings = require dirname(__DIR__) . '/includes/settings.php';
$dbOk = false;
$userCount = null;
try {
    if (gos_table_exists('users')) {
        $dbOk = true;
        $userCount = gos_user_count();
    }
} catch (Throwable) {
    $dbOk = false;
}

$bridge = null;
$healthUrl = (string) ($settings['bridge_health_url'] ?? '');
if ($healthUrl !== '') {
    $ctx = stream_context_create(['http' => ['timeout' => 1.5, 'ignore_errors' => true]]);
    $raw = @file_get_contents($healthUrl, false, $ctx);
    if (is_string($raw) && $raw !== '') {
        $decoded = json_decode($raw, true);
        $bridge = is_array($decoded) ? $decoded : ['raw' => $raw];
    }
}

gos_api_json([
    'ok' => true,
    'product' => 'grokifyos',
    'app' => $settings['app_name'] ?? 'GrokifyOS',
    'auth' => 'password',
    'needs_setup' => gos_needs_setup(),
    'db' => ['ok' => $dbOk, 'users' => $userCount],
    'bridge' => $bridge,
    'time' => gmdate('c'),
]);
