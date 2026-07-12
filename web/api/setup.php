<?php

declare(strict_types=1);

// First-admin bootstrap — only when zero users exist.
require_once dirname(__DIR__) . '/includes/bootstrap.php';
header('Content-Type: application/json; charset=utf-8');

function gos_setup_json(mixed $data, int $code = 200): never
{
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    gos_setup_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

if (!gos_table_exists('users')) {
    gos_setup_json([
        'ok' => false,
        'error' => 'not_migrated',
        'message' => 'Run schema install first: php scripts/install.php',
    ], 503);
}

if (gos_user_count() > 0) {
    gos_setup_json(['ok' => false, 'error' => 'already_setup'], 409);
}

$raw = file_get_contents('php://input') ?: '';
$body = json_decode($raw, true);
if (!is_array($body)) {
    $body = $_POST;
}

$username = (string) ($body['username'] ?? '');
$password = (string) ($body['password'] ?? '');
$display = (string) ($body['display_name'] ?? '');

try {
    $user = gos_create_user($username, $password, 'admin', $display);
} catch (InvalidArgumentException $e) {
    gos_setup_json(['ok' => false, 'error' => $e->getMessage()], 400);
} catch (Throwable $e) {
    gos_setup_json(['ok' => false, 'error' => 'create_failed', 'message' => $e->getMessage()], 500);
}

gos_login_user($user);
gos_setup_json([
    'ok' => true,
    'user' => gos_public_user($user),
    'message' => 'Admin account created. You are logged in.',
]);
