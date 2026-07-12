<?php

declare(strict_types=1);

require_once __DIR__ . '/paths.php';

return [
    'db_host' => gos_env('GROKIFY_DB_HOST', '127.0.0.1') ?? '127.0.0.1',
    'db_port' => (int) (gos_env('GROKIFY_DB_PORT', '3306') ?? '3306'),
    'db_name' => gos_env('GROKIFY_DB_NAME', 'grokifyos') ?? 'grokifyos',
    'db_user' => gos_env('GROKIFY_DB_USER', 'grokifyos') ?? 'grokifyos',
    'db_pass' => gos_env('GROKIFY_DB_PASS', '') ?? '',
    'db_charset' => 'utf8mb4',
    'app_name' => gos_env('GROKIFY_APP_NAME', 'GrokifyOS') ?? 'GrokifyOS',
    'bridge_url' => rtrim(gos_env('GROKIFY_BRIDGE_URL', 'http://127.0.0.1:8766') ?? 'http://127.0.0.1:8766', '/'),
    'bridge_health_url' => gos_env('GROKIFY_BRIDGE_HEALTH', 'http://127.0.0.1:8766/health') ?? 'http://127.0.0.1:8766/health',
    'ws_path' => gos_env('GROKIFY_WS_PATH', '/grokify-ws/') ?? '/grokify-ws/',
];
