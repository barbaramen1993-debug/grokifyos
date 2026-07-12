<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_access();
if ($access['auth'] !== 'session') {
    gos_api_json(['ok' => false, 'error' => 'session_required'], 403);
}
if (($access['user']['role'] ?? '') !== 'admin') {
    gos_api_json(['ok' => false, 'error' => 'admin_required'], 403);
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

if (empty($_FILES['apk']) || !is_array($_FILES['apk'])) {
    gos_api_json(['ok' => false, 'error' => 'apk_required'], 400);
}

$file = $_FILES['apk'];
if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
    gos_api_json(['ok' => false, 'error' => 'upload_failed', 'code' => $file['error'] ?? null], 400);
}

$versionCode = (int) ($_POST['version_code'] ?? 0);
$versionName = trim((string) ($_POST['version_name'] ?? ''));
$changelog = trim((string) ($_POST['changelog'] ?? ''));

if ($versionCode < 1 || $versionName === '' || mb_strlen($versionName) > 32) {
    gos_api_json(['ok' => false, 'error' => 'validation'], 400);
}

$tmp = (string) ($file['tmp_name'] ?? '');
if ($tmp === '' || !is_uploaded_file($tmp)) {
    gos_api_json(['ok' => false, 'error' => 'invalid_upload'], 400);
}

$orig = (string) ($file['name'] ?? 'app.apk');
if (!preg_match('/\.apk$/i', $orig)) {
    gos_api_json(['ok' => false, 'error' => 'must_be_apk'], 400);
}

$dir = gos_root() . '/storage/apk';
if (!is_dir($dir) && !mkdir($dir, 0750, true) && !is_dir($dir)) {
    gos_api_json(['ok' => false, 'error' => 'storage_unavailable'], 500);
}

$safeName = 'grokifyos-' . $versionCode . '-' . preg_replace('/[^a-zA-Z0-9._-]/', '_', $versionName) . '.apk';
$dest = $dir . '/' . $safeName;
if (!move_uploaded_file($tmp, $dest)) {
    gos_api_json(['ok' => false, 'error' => 'move_failed'], 500);
}

$size = (int) filesize($dest);
$sha = hash_file('sha256', $dest) ?: '';
$userId = (int) $access['user']['id'];

try {
    $st = gos_pdo()->prepare(
        'INSERT INTO grokify_apk_releases
            (version_code, version_name, file_name, file_path, file_size, sha256, changelog, is_active, created_by)
         VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
         ON DUPLICATE KEY UPDATE
            version_name = VALUES(version_name),
            file_name = VALUES(file_name),
            file_path = VALUES(file_path),
            file_size = VALUES(file_size),
            sha256 = VALUES(sha256),
            changelog = VALUES(changelog),
            is_active = 1,
            created_by = VALUES(created_by)'
    );
    $st->execute([$versionCode, $versionName, $safeName, $dest, $size, $sha, $changelog !== '' ? $changelog : null, $userId]);
} catch (Throwable $e) {
    @unlink($dest);
    gos_api_json(['ok' => false, 'error' => 'db_failed', 'message' => $e->getMessage()], 500);
}

gos_api_json([
    'ok' => true,
    'release' => [
        'version_code' => $versionCode,
        'version_name' => $versionName,
        'file_name' => $safeName,
        'file_size' => $size,
        'sha256' => $sha,
    ],
]);
