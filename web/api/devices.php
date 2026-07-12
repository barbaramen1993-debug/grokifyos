<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_access();
$user = $access['user'];
$userId = (int) $user['id'];
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$action = (string) ($_GET['action'] ?? '');

if ($method === 'GET') {
    if ($access['auth'] === 'token' && $access['device']) {
        $d = $access['device'];
        gos_api_json(['ok' => true, 'devices' => [[
            'id' => (int) $d['id'],
            'device_name' => $d['device_name'],
            'token_prefix' => $d['token_prefix'],
            'app_version_name' => $d['app_version_name'] ?? null,
            'app_version_code' => $d['app_version_code'] ?? null,
            'last_seen_at' => $d['last_seen_at'] ?? null,
            'created_at' => $d['created_at'] ?? null,
            'revoked_at' => $d['revoked_at'] ?? null,
        ]]]);
    }
    $pack = gos_devices_for_user($userId);
    gos_api_json(['ok' => true, 'devices' => $pack['devices']]);
}

if ($method === 'POST' && $action === 'heartbeat') {
    $body = gos_json_body();
    $deviceId = $access['device'] ? (int) $access['device']['id'] : (int) ($body['device_id'] ?? 0);
    if ($deviceId < 1) {
        gos_api_json(['ok' => false, 'error' => 'device_required'], 400);
    }
    $owned = gos_device_by_id($deviceId);
    if ($owned === null || (int) $owned['user_id'] !== $userId || !empty($owned['revoked_at'])) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $vName = isset($body['app_version_name']) ? (string) $body['app_version_name'] : null;
    $vCode = isset($body['app_version_code']) ? (int) $body['app_version_code'] : null;
    gos_touch_device($deviceId, $vName, $vCode);
    gos_api_json(['ok' => true]);
}

if ($method === 'POST') {
    if ($access['auth'] !== 'session') {
        gos_api_json(['ok' => false, 'error' => 'session_required'], 403);
    }
    $body = gos_json_body();
    $name = (string) ($body['device_name'] ?? $body['name'] ?? 'Android');
    try {
        $created = gos_create_device($userId, $name);
    } catch (Throwable $e) {
        gos_api_json(['ok' => false, 'error' => 'create_failed', 'message' => $e->getMessage()], 500);
    }
    gos_api_json([
        'ok' => true,
        'token' => $created['token'],
        'device' => [
            'id' => (int) $created['device']['id'],
            'device_name' => $created['device']['device_name'],
            'token_prefix' => $created['device']['token_prefix'],
            'created_at' => $created['device']['created_at'],
        ],
        'message' => 'Copy the token now; it is shown only once.',
    ]);
}

if ($method === 'DELETE') {
    if ($access['auth'] !== 'session' && empty($access['device'])) {
        gos_api_json(['ok' => false, 'error' => 'forbidden'], 403);
    }
    $body = gos_json_body();
    $id = (int) ($body['id'] ?? $_GET['id'] ?? 0);
    if ($id <= 0) {
        gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
    }
    if ($access['auth'] === 'token' && $access['device'] && (int) $access['device']['id'] !== $id) {
        gos_api_json(['ok' => false, 'error' => 'forbidden'], 403);
    }
    $stmt = gos_pdo()->prepare(
        'UPDATE grokify_devices SET revoked_at = NOW() WHERE id = ? AND user_id = ? AND revoked_at IS NULL'
    );
    $stmt->execute([$id, $userId]);
    gos_api_json(['ok' => true, 'revoked' => $stmt->rowCount() > 0]);
}

gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
