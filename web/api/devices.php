<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_access();
$user = $access['user'];
$userId = (int) $user['id'];
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    $stmt = gos_pdo()->prepare(
        'SELECT id, device_name, token_prefix, app_version_name, app_version_code,
                last_seen_at, last_ip, created_at, revoked_at
         FROM grokify_devices WHERE user_id = ? ORDER BY created_at DESC'
    );
    $stmt->execute([$userId]);
    $rows = $stmt->fetchAll() ?: [];
    gos_api_json(['ok' => true, 'devices' => $rows]);
}

if ($method === 'POST') {
    // Only session auth can mint new device tokens (not another device)
    if ($access['auth'] !== 'session') {
        gos_api_json(['ok' => false, 'error' => 'session_required'], 403);
    }
    $body = gos_json_body();
    $name = (string) ($body['device_name'] ?? 'Android');
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
    if ($access['auth'] !== 'session') {
        gos_api_json(['ok' => false, 'error' => 'session_required'], 403);
    }
    $body = gos_json_body();
    $id = (int) ($body['id'] ?? $_GET['id'] ?? 0);
    if ($id <= 0) {
        gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
    }
    $stmt = gos_pdo()->prepare(
        'UPDATE grokify_devices SET revoked_at = NOW() WHERE id = ? AND user_id = ? AND revoked_at IS NULL'
    );
    $stmt->execute([$id, $userId]);
    gos_api_json(['ok' => true, 'revoked' => $stmt->rowCount() > 0]);
}

gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
