<?php

declare(strict_types=1);

require_once dirname(__DIR__) . '/includes/bootstrap.php';
header('Content-Type: application/json; charset=utf-8');

function gos_login_json(mixed $data, int $code = 200): never
{
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    gos_login_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

if (gos_needs_setup()) {
    gos_login_json(['ok' => false, 'error' => 'needs_setup'], 503);
}

$raw = file_get_contents('php://input') ?: '';
$body = json_decode($raw, true);
if (!is_array($body)) {
    $body = $_POST;
}

$username = (string) ($body['username'] ?? '');
$password = (string) ($body['password'] ?? '');

$user = gos_verify_password($username, $password);
if ($user === null) {
    usleep(200000);
    gos_login_json(['ok' => false, 'error' => 'invalid_credentials'], 401);
}

gos_login_user($user);
gos_login_json([
    'ok' => true,
    'user' => gos_public_user($user),
]);
