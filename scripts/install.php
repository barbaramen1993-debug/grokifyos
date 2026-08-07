<?php

declare(strict_types=1);

/**
 * Apply schema/*.sql migrations (skip already-applied) and optionally create first admin.
 *
 * Usage:
 *   php scripts/install.php
 *   php scripts/install.php --admin=alice --password=secretpass
 */

$root = dirname(__DIR__);
require_once $root . '/web/includes/paths.php';

$settings = require $root . '/web/includes/settings.php';
$schemaDir = $root . '/schema';
$schemaFiles = glob($schemaDir . '/*.sql') ?: [];
sort($schemaFiles, SORT_STRING);

if ($schemaFiles === []) {
    fwrite(STDERR, "Missing schema files in {$schemaDir}\n");
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

$applied = [];
try {
    $st = $pdo->query('SELECT id FROM schema_migrations');
    if ($st) {
        while ($row = $st->fetch(PDO::FETCH_ASSOC)) {
            if (!empty($row['id'])) {
                $applied[(string) $row['id']] = true;
            }
        }
    }
} catch (Throwable $e) {
    // schema_migrations missing until 001_init runs
}

foreach ($schemaFiles as $sqlFile) {
    $label = basename($sqlFile);
    $migrationId = pathinfo($label, PATHINFO_FILENAME);
    if (isset($applied[$migrationId])) {
        echo "  skip {$label} (already applied)\n";
        continue;
    }
    $sql = file_get_contents($sqlFile);
    if ($sql === false) {
        fwrite(STDERR, "Could not read schema {$label}\n");
        exit(1);
    }
    $parts = array_filter(array_map('trim', explode(';', $sql)));
    foreach ($parts as $stmt) {
        if ($stmt === '') {
            continue;
        }
        // Strip full-line SQL comments, then skip empty leftovers
        $noComments = trim((string) preg_replace('/^\s*--.*$/m', '', $stmt));
        if ($noComments === '') {
            continue;
        }
        $pdo->exec($noComments);
    }
    echo "  applied {$label}\n";
    $applied[$migrationId] = true;
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
