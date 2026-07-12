<?php

declare(strict_types=1);

/**
 * Load KEY=VALUE env files (only keys not already set).
 * Order: /etc/grokifyos/php.env, then project .env
 */
function gos_load_env_files(): void
{
    static $done = false;
    if ($done) {
        return;
    }
    $done = true;

    $files = [
        '/etc/grokifyos/php.env',
        dirname(__DIR__, 2) . '/.env',
    ];

    foreach ($files as $path) {
        if (!is_readable($path)) {
            continue;
        }
        $lines = file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        if ($lines === false) {
            continue;
        }
        foreach ($lines as $line) {
            $line = trim($line);
            if ($line === '' || str_starts_with($line, '#')) {
                continue;
            }
            if (!str_contains($line, '=')) {
                continue;
            }
            [$key, $value] = explode('=', $line, 2);
            $key = trim($key);
            if ($key === '' || getenv($key) !== false) {
                continue;
            }
            $value = trim($value);
            if (
                (str_starts_with($value, '"') && str_ends_with($value, '"'))
                || (str_starts_with($value, "'") && str_ends_with($value, "'"))
            ) {
                $value = substr($value, 1, -1);
            }
            putenv($key . '=' . $value);
            $_ENV[$key] = $value;
            $_SERVER[$key] = $value;
        }
    }
}

gos_load_env_files();

function gos_env(string $key, ?string $default = null): ?string
{
    $v = getenv($key);
    if ($v === false || $v === '') {
        return $default;
    }
    return $v;
}

/** Project root (parent of web/). */
function gos_root(): string
{
    return dirname(__DIR__, 2);
}

function gos_web_base(): string
{
    $env = gos_env('GROKIFY_WEB_BASE', '');
    return $env === null || $env === '' ? '' : rtrim($env, '/');
}

function gos_site_url(): string
{
    $env = gos_env('GROKIFY_SITE_URL');
    if (is_string($env) && $env !== '') {
        return rtrim($env, '/');
    }
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');
    $host = (string) ($_SERVER['HTTP_HOST'] ?? 'localhost');
    return ($https ? 'https' : 'http') . '://' . $host . gos_web_base();
}
