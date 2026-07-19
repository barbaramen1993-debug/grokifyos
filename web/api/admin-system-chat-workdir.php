<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$userId = (int) $access['user']['id'];
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method !== 'GET' && $method !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

// GET ?list=1&path=/foo → browse children; plain GET → current cwd
if ($method === 'GET') {
    $list = isset($_GET['list']) && (string) $_GET['list'] !== '0' && (string) $_GET['list'] !== '';
    if ($list) {
        $path = isset($_GET['path']) ? (string) $_GET['path'] : '';
        $result = gos_bridge_work_dir_list($path);
        if (empty($result['ok'])) {
            gos_api_json([
                'ok' => false,
                'error' => (string) ($result['error'] ?? 'bridge_unavailable'),
                'message' => (string) ($result['message'] ?? ''),
            ], !empty($result['http_code']) && (int) $result['http_code'] >= 400 ? (int) $result['http_code'] : 502);
        }
        gos_api_json([
            'ok' => true,
            'path' => (string) ($result['path'] ?? ''),
            'parent' => $result['parent'] ?? null,
            'default_path' => (string) ($result['default_path'] ?? ''),
            'entries' => is_array($result['entries'] ?? null) ? $result['entries'] : [],
        ]);
    }

    $result = gos_bridge_work_dir_get();
    if (empty($result['ok'])) {
        gos_api_json([
            'ok' => false,
            'error' => (string) ($result['error'] ?? 'bridge_unavailable'),
        ], 502);
    }
    gos_api_json([
        'ok' => true,
        'path' => (string) ($result['path'] ?? ''),
        'default_path' => (string) ($result['default_path'] ?? ''),
        'is_default' => !empty($result['is_default']),
    ]);
}

// POST { path } | { reset: true }
$body = gos_json_body();
$reset = !empty($body['reset']) || (array_key_exists('path', $body) && ($body['path'] === '' || $body['path'] === null));
$path = $reset ? '' : trim((string) ($body['path'] ?? ''));

if (!$reset && $path === '') {
    gos_api_json(['ok' => false, 'error' => 'path_required'], 400);
}

$result = gos_bridge_work_dir_set($path, $reset);
if (empty($result['ok'])) {
    $code = 400;
    $err = (string) ($result['error'] ?? 'failed');
    if ($err === 'bridge_unavailable' || $err === 'empty_response' || str_contains($err, 'curl')) {
        $code = 502;
    }
    gos_api_json([
        'ok' => false,
        'error' => $err,
        'message' => (string) ($result['message'] ?? ''),
    ], $code);
}

gos_system_chat_audit('info', 'access', 'Agent working directory updated', [
    'path' => $result['path'] ?? null,
    'is_default' => !empty($result['is_default']),
    'reset' => $reset,
], $userId);

gos_api_json([
    'ok' => true,
    'path' => (string) ($result['path'] ?? ''),
    'default_path' => (string) ($result['default_path'] ?? ''),
    'is_default' => !empty($result['is_default']),
]);
