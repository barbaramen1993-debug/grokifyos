#!/usr/bin/env php
<?php

declare(strict_types=1);

/**
 * Register a built APK as the active Grokify release (downloadable from the dashboard).
 *
 * Usage:
 *   php publish-apk.php --apk=/path/to.apk --version-code=1 --version-name=0.1.0
 *   php publish-apk.php --apk=... --auto   # bump version_code from latest+1
 *   php publish-apk.php --from-debug       # use app/build/outputs/apk/debug/app-debug.apk
 */

$root = dirname(__DIR__, 2);
require_once $root . '/includes/init.php';

if (!function_exists('grokify_register_apk_upload')) {
    fwrite(STDERR, "Grokify helpers not loaded\n");
    exit(1);
}

$opts = getopt('', [
    'apk:',
    'version-code:',
    'version-name:',
    'changelog:',
    'user-id:',
    'min-sdk:',
    'auto',
    'from-debug',
    'from-release',
    'help',
]);

if (isset($opts['help'])) {
    echo <<<TXT
Publish Grokify APK to the download store.

  --apk=PATH            Path to .apk
  --from-debug          Use android debug APK output
  --from-release        Use android release APK output
  --version-code=N      Integer versionCode (must increase for OTA)
  --version-name=S      e.g. 0.1.0 or 0.1.0-debug
  --changelog=TEXT      Optional notes
  --auto                Auto-bump version-code from latest active + 1
  --user-id=N           created_by (default: first head_admin)
  --min-sdk=N           Optional minSdk

TXT;
    exit(0);
}

$androidRoot = dirname(__DIR__);
$apk = (string) ($opts['apk'] ?? '');
if (isset($opts['from-debug'])) {
    $apk = $androidRoot . '/app/build/outputs/apk/debug/app-debug.apk';
}
if (isset($opts['from-release'])) {
    $apk = $androidRoot . '/app/build/outputs/apk/release/app-release-unsigned.apk';
}

if ($apk === '' || !is_readable($apk)) {
    fwrite(STDERR, "APK not found or unreadable: {$apk}\n");
    exit(1);
}

$versionName = trim((string) ($opts['version-name'] ?? ''));
$versionCode = isset($opts['version-code']) ? (int) $opts['version-code'] : 0;
$changelog = isset($opts['changelog']) ? (string) $opts['changelog'] : null;
$minSdk = isset($opts['min-sdk']) ? (int) $opts['min-sdk'] : 26;

// Prefer versionCode / versionName from android/app/build.gradle.kts when present.
$gradlePath = $androidRoot . '/app/build.gradle.kts';
$gradleCode = 0;
$gradleName = '';
if (is_readable($gradlePath)) {
    $gradle = (string) file_get_contents($gradlePath);
    if (preg_match('/versionCode\s*=\s*(\d+)/', $gradle, $m)) {
        $gradleCode = (int) $m[1];
    }
    if (preg_match('/versionName\s*=\s*"([^"]+)"/', $gradle, $m)) {
        $gradleName = trim($m[1]);
    }
}

if ($versionCode < 1) {
    if ($gradleCode > 0) {
        $versionCode = $gradleCode;
    } elseif (isset($opts['auto'])) {
        $latest = grokify_latest_apk();
        $versionCode = $latest ? ((int) $latest['version_code'] + 1) : 1;
    }
}

if ($versionName === '') {
    $base = basename($apk);
    $isDebug = str_contains($base, 'debug');
    if ($gradleName !== '') {
        $versionName = $gradleName . ($isDebug && !str_contains($gradleName, 'debug') ? '-debug' : '');
    } else {
        $versionName = sprintf('0.1.%d%s', max(1, $versionCode), $isDebug ? '-debug' : '');
    }
}

// --auto: ensure store version_code never goes backwards vs latest release
if (isset($opts['auto'])) {
    $latest = grokify_latest_apk();
    $latestCode = $latest ? (int) $latest['version_code'] : 0;
    if ($versionCode <= $latestCode) {
        $versionCode = $latestCode + 1;
        if ($gradleName !== '') {
            $versionName = $gradleName . (str_contains(basename($apk), 'debug') && !str_contains($gradleName, 'debug') ? '-debug' : '');
        }
    }
}

$userId = isset($opts['user-id']) ? (int) $opts['user-id'] : 0;
if ($userId < 1) {
    $st = grokpot_pdo()->query("SELECT id FROM users WHERE role = 'head_admin' ORDER BY id ASC LIMIT 1");
    $row = $st ? $st->fetch(PDO::FETCH_ASSOC) : false;
    $userId = $row ? (int) $row['id'] : 1;
}

$result = grokify_register_apk_upload(
    $apk,
    basename($apk),
    $versionCode,
    $versionName,
    $changelog,
    $userId,
    $minSdk
);

if (empty($result['ok'])) {
    fwrite(STDERR, 'Publish failed: ' . ($result['error'] ?? 'unknown') . "\n");
    exit(1);
}

$rel = $result['release'];
$origin = grokify_public_origin();
echo "Published Grokify APK\n";
echo "  version: {$rel['version_name']} (code {$rel['version_code']})\n";
echo "  size:    " . number_format((int) $rel['file_size'] / 1048576, 2) . " MB\n";
echo "  sha256:  {$rel['sha256']}\n";
echo "  file:    {$rel['file_path']}\n";
echo "  download (logged-in browser):\n";
echo "    {$origin}/api/grokify-apk-download.php\n";
echo "  dashboard:\n";
echo "    {$origin}/#build\n";
exit(0);
