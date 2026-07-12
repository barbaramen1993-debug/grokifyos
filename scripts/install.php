<?php

declare(strict_types=1);

/**
 * Apply schema/001_init.sql and optionally create first admin.
 *
 * Usage:
 *   php scripts/install.php
 *   php scripts/install.php --admin=alice --password=secretpass
 */

$root = dirname(__DIR__);
require_once $root . '/web/includes/paths.php';

$settings = require $root . '/web/includes/settings.php';
$sqlFile = $root . '/schema/001_init.sql';

if (!is_readable($sqlFile)) {
    fwrite(STDERR, "Missing schema file: {$sqlFile}\n");
    exit(1);
}

$host = $settings['db_host'];
$port = (int) $settings['db_port'];
$name = $settings['db_name'];
$user = $settings['db_user'];
$pass = $settings['db_pass'];

echo "GrokifyOS install\n";
echo "  DB: {$user}@{$host}:{$port}/{$name}\n";

try {
    $pdo = new PDO(
        sprintf('mysql:host=%s;port=%d;charset=utf8mb4', $host, $port),
        $user,
        $pass,
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );
} catch (Throwable $e) {
    fwrite(STDERR, "Connect failed: {$e->getMessage()}\n");
    fwrite(STDERR, "Create the MySQL user/database first, then set GROKIFY_DB_* in .env\n");
    exit(1);
}

$pdo->exec("CREATE DATABASE IF NOT EXISTS `{$name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
$pdo->exec("USE `{$name}`");

$sql = file_get_contents($sqlFile);
if ($sql === false) {
    fwrite(STDERR, "Could not read schema\n");
    exit(1);
}

// Split on semicolons carefully enough for our schema file
$parts = array_filter(array_map('trim', explode(';', $sql)));
foreach ($parts as $stmt) {
    if ($stmt === '' || str_starts_with($stmt, '--')) {
        continue;
    }
    // Skip pure comment blocks
    $noComments = preg_replace('/^--.*$/m', '', $stmt);
    if (trim((string) $noComments) === '') {
        continue;
    }
    $pdo->exec($stmt);
}

echo "Schema applied.\n";

require_once $root . '/web/includes/db.php';
require_once $root . '/web/includes/auth.php';

$adminUser = null;
$adminPass = null;
foreach ($argv as $arg) {
    if (str_starts_with($arg, '--admin=')) {
        $adminUser = substr($arg, 8);
    }
    if (str_starts_with($arg, '--password=')) {
        $adminPass = substr($arg, 11);
    }
}

if ($adminUser && $adminPass) {
    if (gos_user_count() > 0) {
        echo "Users already exist; skipping admin create.\n";
    } else {
        $u = gos_create_user($adminUser, $adminPass, 'admin');
        echo "Admin created: {$u['username']} (id={$u['id']})\n";
    }
} else {
    $n = gos_user_count();
    echo $n === 0
        ? "No users yet. Open the web UI or re-run with --admin=name --password=...\n"
        : "Users: {$n}\n";
}

echo "Done.\n";
