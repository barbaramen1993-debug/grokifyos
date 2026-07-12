<?php

declare(strict_types=1);

/**
 * PHP built-in server router for local smoke tests.
 * Usage: php -S 127.0.0.1:8787 scripts/dev-router.php
 */

$uri = urldecode(parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/');
$root = dirname(__DIR__) . '/web';

if (str_starts_with($uri, '/api/')) {
    $file = $root . $uri;
    if (is_file($file)) {
        require $file;
        return true;
    }
    http_response_code(404);
    header('Content-Type: application/json');
    echo json_encode(['ok' => false, 'error' => 'not_found']);
    return true;
}

if (str_starts_with($uri, '/assets/')) {
    $file = $root . $uri;
    if (is_file($file)) {
        return false; // let built-in server serve static
    }
}

if ($uri === '/' || $uri === '') {
    require $root . '/public/index.php';
    return true;
}

$file = $root . '/public' . $uri;
if (is_file($file)) {
    return false;
}

http_response_code(404);
echo 'Not found';
return true;
