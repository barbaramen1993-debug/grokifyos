<?php

declare(strict_types=1);

/**
 * System chat helpers for GrokifyOS (sessions, audit, bridge, Grok Build usage).
 */

function gos_system_chat_tables_ready(): bool
{
    static $ready = null;
    if ($ready !== null) {
        return $ready;
    }
    $ready = gos_table_exists('system_chat_sessions')
        && gos_table_exists('system_chat_messages')
        && gos_table_exists('system_chat_events');

    return $ready;
}

/** @return array{user: array, device: ?array, auth: string} */
function gos_require_system_chat(): array
{
    $access = gos_require_access();
    if (!gos_system_chat_tables_ready()) {
        gos_api_json(['ok' => false, 'error' => 'system_chat_not_migrated'], 503);
    }

    return $access;
}

function gos_system_chat_session_id(): string
{
    return bin2hex(random_bytes(16));
}

function gos_system_chat_valid_session_id(string $id): bool
{
    return (bool) preg_match('/^[a-f0-9]{32}$/', $id);
}

function gos_system_chat_auto_title(string $content, int $maxLen = 48): string
{
    $text = trim(preg_replace('/\s+/u', ' ', str_replace(["\r\n", "\r"], "\n", $content)) ?? '');
    if ($text === '') {
        return '';
    }
    if (preg_match('/^(.{1,' . max(12, $maxLen) . '}?)(?:[.!?](?:\s|$)|$)/u', $text, $m)) {
        $candidate = trim($m[1]);
        if ($candidate !== '') {
            $text = $candidate;
        }
    }
    if (mb_strlen($text) > $maxLen) {
        $text = rtrim(mb_substr($text, 0, $maxLen - 1)) . '…';
    }

    return $text;
}

function gos_system_chat_ws_secret(): string
{
    $fromEnv = gos_env('GROKIFY_WS_AUTH_SECRET', '') ?? '';
    if ($fromEnv !== '') {
        return $fromEnv;
    }
    $pepper = gos_env('GROKIFY_SECRETS_PEPPER', '') ?? '';
    if ($pepper !== '') {
        return hash('sha256', 'grokifyos_system_chat_ws:' . $pepper);
    }

    return hash('sha256', 'grokifyos_system_chat_ws_fallback');
}

function gos_system_chat_ws_token(array $user, int $ttlSeconds = 3600): string
{
    $payload = [
        'uid' => (int) ($user['id'] ?? 0),
        'role' => (string) ($user['role'] ?? ''),
        'exp' => time() + $ttlSeconds,
        'nonce' => bin2hex(random_bytes(8)),
    ];
    $json = json_encode($payload, JSON_UNESCAPED_SLASHES);
    $sig = hash_hmac('sha256', (string) $json, gos_system_chat_ws_secret());

    return base64_encode((string) $json) . '.' . $sig;
}

/** @return array{uid: int, role: string}|null */
function gos_system_chat_verify_ws_token(string $token): ?array
{
    $parts = explode('.', $token, 2);
    if (count($parts) !== 2) {
        return null;
    }
    $json = base64_decode($parts[0], true);
    if ($json === false) {
        return null;
    }
    $expected = hash_hmac('sha256', $json, gos_system_chat_ws_secret());
    if (!hash_equals($expected, $parts[1])) {
        return null;
    }
    $data = json_decode($json, true);
    if (!is_array($data) || empty($data['uid']) || empty($data['exp'])) {
        return null;
    }
    if ((int) $data['exp'] < time()) {
        return null;
    }

    return ['uid' => (int) $data['uid'], 'role' => (string) ($data['role'] ?? '')];
}

function gos_setting_get(string $key, string $default = ''): string
{
    if (!gos_table_exists('app_settings')) {
        return $default;
    }
    $st = gos_pdo()->prepare('SELECT setting_value FROM app_settings WHERE setting_key = ? LIMIT 1');
    $st->execute([$key]);
    $v = $st->fetchColumn();

    return is_string($v) ? $v : $default;
}

function gos_setting_set(string $key, string $value): void
{
    if (!gos_table_exists('app_settings')) {
        return;
    }
    $st = gos_pdo()->prepare(
        'INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)
         ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)'
    );
    $st->execute([$key, $value]);
}

function gos_system_chat_selected_model(): string
{
    $m = gos_setting_get('system_chat_selected_model', '');
    if ($m === '' || $m === 'auto') {
        return '';
    }
    if (!str_starts_with($m, 'gb:') && !str_starts_with($m, 'grok:')) {
        return '';
    }
    if (str_starts_with($m, 'grok:') && !str_starts_with($m, 'gb:')) {
        return 'gb:' . substr($m, 5);
    }

    return $m;
}

function gos_system_chat_set_selected_model(string $model): void
{
    $model = trim($model);
    if ($model === '' || $model === 'auto') {
        return;
    }
    if (!str_starts_with($model, 'gb:') && !str_starts_with($model, 'grok:')) {
        return;
    }
    if (str_starts_with($model, 'grok:') && !str_starts_with($model, 'gb:')) {
        $model = 'gb:' . substr($model, 5);
    }
    gos_setting_set('system_chat_selected_model', $model);
}

function gos_system_chat_bridge_url(): string
{
    $url = gos_env('GROKIFY_BRIDGE_URL', '') ?? '';
    if ($url !== '') {
        return rtrim($url, '/');
    }

    return 'http://127.0.0.1:8766';
}

function gos_system_chat_ws_path(): string
{
    $path = gos_env('GROKIFY_WS_PATH', '/grokify-ws/') ?? '/grokify-ws/';
    if ($path === '') {
        $path = '/grokify-ws/';
    }
    if ($path[0] !== '/') {
        $path = '/' . $path;
    }
    if (!str_ends_with($path, '/')) {
        $path .= '/';
    }

    return gos_web_base() . $path;
}

/**
 * @param array<string, mixed> $context
 */
function gos_system_chat_audit(
    string $level,
    string $category,
    string $message,
    array $context = [],
    ?int $userId = null,
    ?string $sessionId = null
): ?int {
    if (!gos_table_exists('system_chat_events')) {
        return null;
    }

    $level = match (strtolower($level)) {
        'debug', 'info', 'notice', 'warning', 'error' => strtolower($level),
        default => 'info',
    };
    $category = substr(preg_replace('/[^a-z0-9_]/', '', strtolower($category)) ?: 'general', 0, 32);
    $message = mb_substr(trim($message), 0, 512);
    if ($message === '') {
        $message = '(empty)';
    }

    $context = gos_system_chat_redact_context($context);
    if ($sessionId !== null && !gos_system_chat_valid_session_id($sessionId)) {
        $sessionId = null;
    }

    try {
        $st = gos_pdo()->prepare(
            'INSERT INTO system_chat_events (level, category, message, context, user_id, session_id)
             VALUES (?, ?, ?, ?, ?, ?)'
        );
        $st->execute([
            $level,
            $category,
            $message,
            $context === [] ? null : json_encode($context, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            $userId,
            $sessionId,
        ]);

        return (int) gos_pdo()->lastInsertId();
    } catch (Throwable $e) {
        error_log('[gos_system_chat_audit] ' . $e->getMessage());

        return null;
    }
}

/**
 * @param array<string, mixed> $context
 * @return array<string, mixed>
 */
function gos_system_chat_redact_context(array $context): array
{
    $out = [];
    foreach ($context as $k => $v) {
        if (is_string($v)) {
            $v = preg_replace('/0x[0-9a-fA-F]{64}\b/', '[REDACTED_KEY]', $v) ?? $v;
            $v = preg_replace('/\b[0-9a-fA-F]{64}\b/', '[REDACTED_KEY]', $v) ?? $v;
            if (strlen($v) > 4000) {
                $v = substr($v, 0, 4000) . '…';
            }
        } elseif (is_array($v)) {
            $v = gos_system_chat_redact_context($v);
        }
        $out[$k] = $v;
    }

    return $out;
}

/** @return list<string> */
function gos_system_chat_active_notes(): array
{
    if (!gos_table_exists('system_chat_notes')) {
        return [];
    }
    $st = gos_pdo()->query('SELECT note_text FROM system_chat_notes WHERE enabled = 1 ORDER BY id ASC');
    $rows = $st ? $st->fetchAll(PDO::FETCH_COLUMN) : [];

    return array_values(array_filter(array_map('strval', $rows ?: [])));
}

function gos_system_chat_session_owned(string $sessionId, int $userId): bool
{
    if (!gos_system_chat_valid_session_id($sessionId)) {
        return false;
    }
    $st = gos_pdo()->prepare('SELECT user_id FROM system_chat_sessions WHERE id = ? LIMIT 1');
    $st->execute([$sessionId]);
    $row = $st->fetch(PDO::FETCH_ASSOC);

    return $row && (int) $row['user_id'] === $userId;
}

/**
 * @return array{events: list<array>, total: int}
 */
function gos_system_chat_audit_list(array $opts): array
{
    $limit = min(500, max(1, (int) ($opts['limit'] ?? 100)));
    $offset = max(0, (int) ($opts['offset'] ?? 0));
    $sinceId = (int) ($opts['since_id'] ?? 0);

    $where = ['1=1'];
    $params = [];

    if (!empty($opts['level'])) {
        $where[] = 'level = ?';
        $params[] = (string) $opts['level'];
    }
    if (!empty($opts['category'])) {
        $where[] = 'category = ?';
        $params[] = (string) $opts['category'];
    }
    if (!empty($opts['session_id']) && gos_system_chat_valid_session_id((string) $opts['session_id'])) {
        $where[] = 'session_id = ?';
        $params[] = (string) $opts['session_id'];
    }
    if ($sinceId > 0) {
        $where[] = 'id > ?';
        $params[] = $sinceId;
    }

    $sqlWhere = implode(' AND ', $where);
    $countSt = gos_pdo()->prepare("SELECT COUNT(*) FROM system_chat_events WHERE {$sqlWhere}");
    $countSt->execute($params);
    $total = (int) $countSt->fetchColumn();

    $order = $sinceId > 0 ? 'ORDER BY id ASC' : 'ORDER BY id DESC';
    $listSt = gos_pdo()->prepare(
        "SELECT id, level, category, message, context, user_id, session_id, created_at
         FROM system_chat_events WHERE {$sqlWhere}
         {$order} LIMIT " . (int) $limit . ' OFFSET ' . (int) $offset
    );
    $listSt->execute($params);
    $events = $listSt->fetchAll(PDO::FETCH_ASSOC) ?: [];
    foreach ($events as &$ev) {
        if (!empty($ev['context']) && is_string($ev['context'])) {
            $ev['context'] = json_decode($ev['context'], true);
        }
    }
    unset($ev);

    return ['events' => $events, 'total' => $total];
}

/** @return list<string> */
function gos_grok_auth_json_candidates(): array
{
    $out = [];
    $env = gos_env('GROKIFY_GROK_AUTH_JSON', '') ?? '';
    if ($env !== '') {
        $out[] = $env;
    }
    $out[] = gos_root() . '/storage/grok-auth.json';
    $out[] = '/etc/grokifyos/grok-auth.json';
    $out[] = '/root/.grok/auth.json';
    $home = getenv('HOME');
    if (is_string($home) && $home !== '' && $home !== '/root') {
        $out[] = rtrim($home, '/') . '/.grok/auth.json';
    }
    $seen = [];
    $uniq = [];
    foreach ($out as $p) {
        if ($p === '' || isset($seen[$p])) {
            continue;
        }
        $seen[$p] = true;
        $uniq[] = $p;
    }

    return $uniq;
}

function gos_grok_auth_json_path(): string
{
    foreach (gos_grok_auth_json_candidates() as $path) {
        if (is_readable($path)) {
            return $path;
        }
    }

    return gos_root() . '/storage/grok-auth.json';
}

/** @return array{entry_key: string, entry: array<string, mixed>, path: string}|null */
function gos_grok_auth_load(): ?array
{
    foreach (gos_grok_auth_json_candidates() as $path) {
        if (!is_readable($path)) {
            continue;
        }
        $raw = @file_get_contents($path);
        if ($raw === false || $raw === '') {
            continue;
        }
        $data = json_decode($raw, true);
        if (!is_array($data) || $data === []) {
            continue;
        }
        foreach ($data as $key => $entry) {
            if (!is_array($entry)) {
                continue;
            }
            $token = (string) ($entry['key'] ?? $entry['access_token'] ?? '');
            if ($token !== '') {
                return ['entry_key' => (string) $key, 'entry' => $entry, 'path' => $path];
            }
        }
    }

    return null;
}

/** @param array<string, mixed> $entry */
function gos_grok_auth_save_entry(string $entryKey, array $entry, ?string $path = null): bool
{
    $path = $path ?: gos_grok_auth_json_path();
    if (!is_writable($path) && !is_writable(dirname($path))) {
        return false;
    }
    $fp = @fopen($path, 'c+');
    if ($fp === false) {
        return false;
    }
    try {
        if (!flock($fp, LOCK_EX)) {
            return false;
        }
        rewind($fp);
        $raw = stream_get_contents($fp);
        $data = is_string($raw) && $raw !== '' ? json_decode($raw, true) : [];
        if (!is_array($data)) {
            $data = [];
        }
        $data[$entryKey] = $entry;
        $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
        if ($json === false) {
            return false;
        }
        ftruncate($fp, 0);
        rewind($fp);
        fwrite($fp, $json . "\n");
        fflush($fp);

        return true;
    } finally {
        flock($fp, LOCK_UN);
        fclose($fp);
    }
}

function gos_grok_auth_token_expired(array $entry, int $skewSeconds = 120): bool
{
    $expiresAt = (string) ($entry['expires_at'] ?? '');
    if ($expiresAt === '') {
        return false;
    }
    $ts = strtotime($expiresAt);
    if ($ts === false) {
        return false;
    }

    return $ts <= (time() + $skewSeconds);
}

/** @return array{ok: bool, token?: string, entry?: array<string, mixed>, error?: string, message?: string, http_code?: int, detail?: mixed} */
function gos_grok_auth_ensure_token(): array
{
    $loaded = gos_grok_auth_load();
    if ($loaded === null) {
        return [
            'ok' => false,
            'error' => 'auth_missing',
            'message' => 'Grok Build auth unavailable — set GROKIFY_GROK_AUTH_JSON or place auth.json (from `grok login`).',
        ];
    }
    $entryKey = $loaded['entry_key'];
    $entry = $loaded['entry'];
    $authPath = isset($loaded['path']) ? (string) $loaded['path'] : null;
    $token = (string) ($entry['key'] ?? $entry['access_token'] ?? '');
    if ($token !== '' && !gos_grok_auth_token_expired($entry)) {
        return ['ok' => true, 'token' => $token, 'entry' => $entry];
    }

    $refresh = (string) ($entry['refresh_token'] ?? '');
    $clientId = (string) ($entry['oidc_client_id'] ?? '');
    if ($refresh === '' || $clientId === '') {
        return $token !== ''
            ? ['ok' => true, 'token' => $token, 'entry' => $entry]
            : ['ok' => false, 'error' => 'auth_refresh_unavailable'];
    }

    $body = http_build_query([
        'grant_type' => 'refresh_token',
        'refresh_token' => $refresh,
        'client_id' => $clientId,
    ]);
    $ch = curl_init('https://auth.x.ai/oauth2/token');
    if ($ch === false) {
        return ['ok' => false, 'error' => 'curl_init_failed'];
    }
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => $body,
        CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded', 'Accept: application/json'],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 20,
    ]);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);
    if (!is_string($resp) || $resp === '' || $code < 200 || $code >= 300) {
        if ($token !== '' && !gos_grok_auth_token_expired($entry, -3600)) {
            return ['ok' => true, 'token' => $token, 'entry' => $entry];
        }

        return ['ok' => false, 'error' => 'auth_refresh_failed', 'http_code' => $code, 'detail' => $err ?: $resp];
    }
    $json = json_decode($resp, true);
    if (!is_array($json) || empty($json['access_token'])) {
        return ['ok' => false, 'error' => 'auth_refresh_parse'];
    }
    $entry['key'] = (string) $json['access_token'];
    if (!empty($json['refresh_token'])) {
        $entry['refresh_token'] = (string) $json['refresh_token'];
    }
    $expiresIn = (int) ($json['expires_in'] ?? 21600);
    $entry['expires_at'] = gmdate('Y-m-d\TH:i:s.u\Z', time() + max(60, $expiresIn));
    gos_grok_auth_save_entry($entryKey, $entry, $authPath);

    return ['ok' => true, 'token' => (string) $entry['key'], 'entry' => $entry];
}

/** @return array<string, mixed> */
function gos_grok_build_fetch_usage(bool $forceRefresh = false): array
{
    static $cache = null;
    static $cacheAt = 0;
    $ttl = 60;
    if (!$forceRefresh && is_array($cache) && (time() - $cacheAt) < $ttl) {
        return $cache;
    }

    $auth = gos_grok_auth_ensure_token();
    if (empty($auth['ok']) || empty($auth['token'])) {
        return [
            'ok' => false,
            'error' => $auth['error'] ?? 'auth_failed',
            'message' => $auth['message'] ?? 'Grok Build auth unavailable — run `grok login` and point GROKIFY_GROK_AUTH_JSON at auth.json.',
        ];
    }

    $url = gos_env('GROKIFY_GROK_BILLING_URL', 'https://cli-chat-proxy.grok.com/v1/billing?format=credits')
        ?? 'https://cli-chat-proxy.grok.com/v1/billing?format=credits';
    $ch = curl_init($url);
    if ($ch === false) {
        return ['ok' => false, 'error' => 'curl_init_failed'];
    }
    curl_setopt_array($ch, [
        CURLOPT_HTTPGET => true,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $auth['token'],
            'Accept: application/json',
            'User-Agent: grokifyos-bridge/usage',
            'x-grok-client-version: 0.2.99',
            'x-grok-client-mode: cli',
        ],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 20,
    ]);
    $resp = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);

    if (!is_string($resp) || $resp === '' || $code < 200 || $code >= 300) {
        if ($code === 401 && !$forceRefresh) {
            $loaded = gos_grok_auth_load();
            if ($loaded !== null) {
                $entry = $loaded['entry'];
                $entry['expires_at'] = gmdate('Y-m-d\TH:i:s\Z', time() - 10);
                gos_grok_auth_save_entry($loaded['entry_key'], $entry, $loaded['path'] ?? null);
            }

            return gos_grok_build_fetch_usage(true);
        }

        return [
            'ok' => false,
            'error' => 'billing_fetch_failed',
            'http_code' => $code,
            'message' => $err ?: 'Billing upstream error',
        ];
    }

    $json = json_decode($resp, true);
    if (!is_array($json)) {
        return ['ok' => false, 'error' => 'billing_parse_failed'];
    }

    $config = is_array($json['config'] ?? null) ? $json['config'] : $json;
    $period = is_array($config['currentPeriod'] ?? null) ? $config['currentPeriod'] : [];
    $products = [];
    if (is_array($config['productUsage'] ?? null)) {
        foreach ($config['productUsage'] as $p) {
            if (!is_array($p)) {
                continue;
            }
            $products[] = [
                'product' => (string) ($p['product'] ?? ''),
                'usage_percent' => isset($p['usagePercent']) ? (float) $p['usagePercent'] : null,
            ];
        }
    }

    $percent = isset($config['creditUsagePercent']) ? (float) $config['creditUsagePercent'] : 0.0;
    $resetAt = (string) ($period['end'] ?? $config['billingPeriodEnd'] ?? '');
    $periodStart = (string) ($period['start'] ?? $config['billingPeriodStart'] ?? '');
    $tier = (string) ($json['subscriptionTier'] ?? $config['subscriptionTier'] ?? '');

    $prepaid = 0.0;
    if (is_array($config['prepaidBalance'] ?? null) && isset($config['prepaidBalance']['val'])) {
        $prepaid = (float) $config['prepaidBalance']['val'];
    }
    $onDemandUsed = 0.0;
    if (is_array($config['onDemandUsed'] ?? null) && isset($config['onDemandUsed']['val'])) {
        $onDemandUsed = (float) $config['onDemandUsed']['val'];
    }
    $onDemandCap = 0.0;
    if (is_array($config['onDemandCap'] ?? null) && isset($config['onDemandCap']['val'])) {
        $onDemandCap = (float) $config['onDemandCap']['val'];
    }

    $out = [
        'ok' => true,
        'usage_percent' => $percent,
        'remaining_percent' => max(0.0, 100.0 - $percent),
        'period_type' => (string) ($period['type'] ?? 'USAGE_PERIOD_TYPE_WEEKLY'),
        'period_start' => $periodStart,
        'period_end' => $resetAt,
        'reset_at' => $resetAt,
        'subscription_tier' => $tier,
        'products' => $products,
        'prepaid_balance' => $prepaid,
        'on_demand_used' => $onDemandUsed,
        'on_demand_cap' => $onDemandCap,
        'is_unified_billing' => !empty($config['isUnifiedBillingUser']),
        'fetched_at' => gmdate('c'),
        'source' => 'cli-chat-proxy',
    ];
    $cache = $out;
    $cacheAt = time();

    return $out;
}

/** @return array{devices: list<array>, active: list<array>} */
function gos_devices_for_user(int $userId): array
{
    if (!gos_table_exists('grokify_devices')) {
        return ['devices' => [], 'active' => []];
    }
    $st = gos_pdo()->prepare(
        'SELECT id, device_name, token_prefix, app_version_name, app_version_code,
                last_seen_at, last_ip, created_at, revoked_at
         FROM grokify_devices WHERE user_id = ? ORDER BY created_at DESC'
    );
    $st->execute([$userId]);
    $devices = $st->fetchAll(PDO::FETCH_ASSOC) ?: [];
    $active = array_values(array_filter($devices, static fn ($d) => empty($d['revoked_at'])));

    return ['devices' => $devices, 'active' => $active];
}

/** @return array<string, mixed>|null */
function gos_latest_apk(): ?array
{
    if (!gos_table_exists('grokify_apk_releases')) {
        return null;
    }
    $st = gos_pdo()->query(
        'SELECT * FROM grokify_apk_releases WHERE is_active = 1 ORDER BY version_code DESC LIMIT 1'
    );
    $row = $st ? $st->fetch(PDO::FETCH_ASSOC) : false;

    return is_array($row) ? $row : null;
}
