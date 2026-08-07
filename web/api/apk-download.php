<?php

declare(strict_types=1);

require_once dirname(__DIR__) . '/includes/bootstrap.php';

$channel = function_exists('gos_apk_channel')
    ? gos_apk_channel(isset($_GET['channel']) ? (string) $_GET['channel'] : 'phone')
    : 'phone';
$apk = gos_latest_apk($channel);
if ($apk === null) {
    http_response_code(404);
    header('Content-Type: text/plain; charset=utf-8');
    echo 'No APK published for channel=' . $channel . '.';
    exit;
}

$path = function_exists('gos_apk_absolute_path')
    ? gos_apk_absolute_path($apk)
    : (string) ($apk['file_path'] ?? '');
if ($path === '' || !is_readable($path)) {
    // Resolve relative to storage/apk
    $alt = gos_root() . '/storage/apk/' . basename((string) ($apk['file_name'] ?? $apk['file_path'] ?? ''));
    if (is_readable($alt)) {
        $path = $alt;
    } else {
        http_response_code(404);
        header('Content-Type: text/plain; charset=utf-8');
        echo 'APK file missing on disk.';
        exit;
    }
}

$name = (string) ($apk['file_name'] ?? 'grokifyos.apk');
$size = (int) ($apk['file_size'] ?? filesize($path));

header('Content-Type: application/vnd.android.package-archive');
header('Content-Disposition: attachment; filename="' . str_replace('"', '', $name) . '"');
header('Content-Length: ' . $size);
header('Cache-Control: private, no-cache');
readfile($path);
exit;
