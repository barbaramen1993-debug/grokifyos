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

$userId = (int) $access['user']['id'];
$result = gos_register_apk_upload(
    $tmp,
    $orig,
    $versionCode,
    $versionName,
    $changelog !== '' ? $changelog : null,
    $userId,
    26
);

if (empty($result['ok'])) {
    gos_api_json(['ok' => false, 'error' => $result['error'] ?? 'upload_failed'], 500);
}

$rel = $result['release'];
gos_api_json([
    'ok' => true,
    'release' => [
        'version_code' => (int) $rel['version_code'],
        'version_name' => (string) $rel['version_name'],
        'file_name' => (string) $rel['file_name'],
        'file_size' => (int) $rel['file_size'],
        'sha256' => (string) $rel['sha256'],
    ],
]);
