<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

/**
 * Download a marketplace script package (zip of package directory, or index.html).
 * Auth: device Bearer or session.
 */
gos_require_method('GET');
$access = gos_require_access();

if (!empty($access['device'])) {
    $vName = isset($_GET['version_name']) ? (string) $_GET['version_name'] : null;
    $vCode = (int) ($_GET['version_code'] ?? 0);
    gos_touch_device((int) $access['device']['id'], $vName, $vCode > 0 ? $vCode : null);
}

$id = trim((string) ($_GET['id'] ?? ''));
if ($id === '' || !preg_match('/^[a-zA-Z0-9][a-zA-Z0-9_\-]{0,63}$/', $id)) {
    gos_api_json(['ok' => false, 'error' => 'invalid_package_id'], 400);
}

// Only serve packages listed in catalog as webview kind.
$catalogPath = gos_root() . '/storage/plugins/catalog.json';
$allowed = false;
if (is_readable($catalogPath)) {
    $decoded = json_decode((string) file_get_contents($catalogPath), true);
    if (is_array($decoded) && is_array($decoded['plugins'] ?? null)) {
        foreach ($decoded['plugins'] as $p) {
            if (!is_array($p)) {
                continue;
            }
            if (array_key_exists('enabled', $p) && !$p['enabled']) {
                continue;
            }
            $kind = strtolower((string) ($p['kind'] ?? ''));
            if ($kind !== 'webview') {
                continue;
            }
            $pkg = trim((string) ($p['package'] ?? $p['id'] ?? ''));
            if ($pkg === $id) {
                $allowed = true;
                break;
            }
        }
    }
}

if (!$allowed) {
    gos_api_json(['ok' => false, 'error' => 'package_not_found'], 404);
}

$dir = gos_root() . '/storage/plugins/packages/' . $id;
$index = $dir . '/index.html';

if (!is_dir($dir) || !is_readable($index)) {
    gos_api_json(['ok' => false, 'error' => 'package_missing_on_disk'], 404);
}

// Prefer zip when ZipArchive is available so multi-file packages work.
if (class_exists(ZipArchive::class)) {
    $tmp = tempnam(sys_get_temp_dir(), 'gos_pkg_');
    if ($tmp === false) {
        gos_api_json(['ok' => false, 'error' => 'temp_failed'], 500);
    }
    $zipPath = $tmp . '.zip';
    @unlink($tmp);

    $zip = new ZipArchive();
    if ($zip->open($zipPath, ZipArchive::CREATE | ZipArchive::OVERWRITE) !== true) {
        gos_api_json(['ok' => false, 'error' => 'zip_open_failed'], 500);
    }

    $iterator = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::LEAVES_ONLY
    );
    foreach ($iterator as $file) {
        /** @var SplFileInfo $file */
        if (!$file->isFile()) {
            continue;
        }
        $full = $file->getRealPath();
        if ($full === false) {
            continue;
        }
        $local = substr($full, strlen(rtrim($dir, '/')) + 1);
        // Zip slip guard: only relative paths inside package dir
        if ($local === '' || str_contains($local, '..')) {
            continue;
        }
        $zip->addFile($full, $local);
    }
    $zip->close();

    $size = filesize($zipPath);
    header('Content-Type: application/zip');
    header('Content-Disposition: attachment; filename="' . $id . '.zip"');
    if ($size !== false) {
        header('Content-Length: ' . $size);
    }
    header('Cache-Control: private, no-cache');
    header('X-Grokify-Package-Format: zip');
    readfile($zipPath);
    @unlink($zipPath);
    exit;
}

// Fallback: single HTML file
$size = filesize($index);
header('Content-Type: text/html; charset=utf-8');
header('Content-Disposition: attachment; filename="index.html"');
if ($size !== false) {
    header('Content-Length: ' . $size);
}
header('Cache-Control: private, no-cache');
header('X-Grokify-Package-Format: html');
readfile($index);
exit;
