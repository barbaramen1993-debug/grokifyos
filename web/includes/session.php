<?php

declare(strict_types=1);

require_once __DIR__ . '/paths.php';

function gos_session_name(): string
{
    return '__grokifyos_sid';
}

function gos_session_save_path(): string
{
    $dir = gos_root() . '/storage/sessions';
    if (!is_dir($dir)) {
        @mkdir($dir, 0770, true);
    }
    return $dir;
}

function gos_session_login_lifetime_seconds(): int
{
    return 30 * 86400;
}

function gos_session_cookie_secure(): bool
{
    return (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');
}

function gos_session_start(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) {
        return;
    }
    $path = gos_session_save_path();
    session_save_path($path);
    session_name(gos_session_name());
    session_set_cookie_params([
        'lifetime' => gos_session_login_lifetime_seconds(),
        'path' => gos_web_base() === '' ? '/' : gos_web_base(),
        'httponly' => true,
        'secure' => gos_session_cookie_secure(),
        'samesite' => 'Lax',
    ]);
    session_start();
}
