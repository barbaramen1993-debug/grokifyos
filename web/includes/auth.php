<?php

declare(strict_types=1);

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/session.php';

/**
 * @return array<string, mixed>|null
 */
function gos_user_by_id(int $id): ?array
{
    if ($id <= 0 || !gos_table_exists('users')) {
        return null;
    }
    $stmt = gos_pdo()->prepare('SELECT * FROM users WHERE id = ? LIMIT 1');
    $stmt->execute([$id]);
    $row = $stmt->fetch();
    return is_array($row) ? $row : null;
}

/**
 * @return array<string, mixed>|null
 */
function gos_user_by_username(string $username): ?array
{
    if ($username === '' || !gos_table_exists('users')) {
        return null;
    }
    $stmt = gos_pdo()->prepare('SELECT * FROM users WHERE username = ? LIMIT 1');
    $stmt->execute([$username]);
    $row = $stmt->fetch();
    return is_array($row) ? $row : null;
}

function gos_user_count(): int
{
    if (!gos_table_exists('users')) {
        return 0;
    }
    return (int) gos_pdo()->query('SELECT COUNT(*) FROM users')->fetchColumn();
}

function gos_needs_setup(): bool
{
    try {
        return !gos_table_exists('users') || gos_user_count() === 0;
    } catch (Throwable) {
        return true;
    }
}

/**
 * @return array<string, mixed>
 */
function gos_public_user(array $user): array
{
    return [
        'id' => (int) $user['id'],
        'username' => (string) $user['username'],
        'display_name' => (string) ($user['display_name'] ?: $user['username']),
        'role' => (string) $user['role'],
        'status' => (string) $user['status'],
    ];
}

/**
 * Create first (or any) admin/user with password.
 *
 * @return array<string, mixed>
 */
function gos_create_user(string $username, string $password, string $role = 'admin', string $displayName = ''): array
{
    $username = strtolower(trim($username));
    if (!preg_match('/^[a-z0-9_]{3,32}$/', $username)) {
        throw new InvalidArgumentException('invalid_username');
    }
    if (strlen($password) < 8) {
        throw new InvalidArgumentException('password_too_short');
    }
    $role = $role === 'user' ? 'user' : 'admin';
    $hash = password_hash($password, PASSWORD_DEFAULT);
    $display = $displayName !== '' ? $displayName : $username;
    $pdo = gos_pdo();
    $stmt = $pdo->prepare(
        'INSERT INTO users (username, display_name, password_hash, role, status) VALUES (?, ?, ?, ?, ?)'
    );
    $stmt->execute([$username, $display, $hash, $role, 'active']);
    $id = (int) $pdo->lastInsertId();
    $user = gos_user_by_id($id);
    if ($user === null) {
        throw new RuntimeException('user_create_failed');
    }
    return $user;
}

/**
 * @return array<string, mixed>|null
 */
function gos_verify_password(string $username, string $password): ?array
{
    $user = gos_user_by_username(strtolower(trim($username)));
    if ($user === null) {
        return null;
    }
    if (($user['status'] ?? '') !== 'active') {
        return null;
    }
    if (!password_verify($password, (string) $user['password_hash'])) {
        return null;
    }
    return $user;
}

function gos_login_user(array $user): void
{
    gos_session_start();
    session_regenerate_id(true);
    $_SESSION['user_id'] = (int) $user['id'];
    $_SESSION['login_at'] = time();
    try {
        $stmt = gos_pdo()->prepare('UPDATE users SET last_login_at = NOW() WHERE id = ?');
        $stmt->execute([(int) $user['id']]);
    } catch (Throwable) {
        // non-fatal
    }
}

function gos_logout(): void
{
    gos_session_start();
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $p = session_get_cookie_params();
        setcookie(session_name(), '', [
            'expires' => time() - 3600,
            'path' => $p['path'] ?? '/',
            'domain' => $p['domain'] ?? '',
            'secure' => (bool) ($p['secure'] ?? false),
            'httponly' => true,
            'samesite' => 'Lax',
        ]);
    }
    session_destroy();
}

/**
 * @return array<string, mixed>|null
 */
function gos_current_user(): ?array
{
    gos_session_start();
    $id = (int) ($_SESSION['user_id'] ?? 0);
    if ($id <= 0) {
        return null;
    }
    $user = gos_user_by_id($id);
    if ($user === null || ($user['status'] ?? '') !== 'active') {
        return null;
    }
    return $user;
}

/** Device Bearer prefix for GrokifyOS tokens. */
function gos_device_token_prefix(): string
{
    return 'gos_';
}

function gos_authorization_header(): string
{
    $authHeader = (string) ($_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '');
    if ($authHeader === '' && function_exists('apache_request_headers')) {
        $headers = apache_request_headers();
        foreach ($headers as $k => $v) {
            if (strtolower((string) $k) === 'authorization') {
                return (string) $v;
            }
        }
    }
    return $authHeader;
}

/**
 * @return array{user: array, device: array}|null
 */
function gos_auth_from_bearer(): ?array
{
    if (!gos_table_exists('grokify_devices')) {
        return null;
    }
    $authHeader = gos_authorization_header();
    if (!preg_match('/^\s*Bearer\s+(\S+)\s*$/i', $authHeader, $m)) {
        return null;
    }
    $token = $m[1];
    $hash = hash('sha256', $token);
    $stmt = gos_pdo()->prepare(
        'SELECT * FROM grokify_devices WHERE token_hash = ? AND revoked_at IS NULL LIMIT 1'
    );
    $stmt->execute([$hash]);
    $device = $stmt->fetch();
    if (!is_array($device)) {
        return null;
    }
    $user = gos_user_by_id((int) $device['user_id']);
    if ($user === null || ($user['status'] ?? '') !== 'active') {
        return null;
    }
    $ip = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
    $upd = gos_pdo()->prepare('UPDATE grokify_devices SET last_seen_at = NOW(), last_ip = ? WHERE id = ?');
    $upd->execute([$ip !== '' ? $ip : null, (int) $device['id']]);

    return ['user' => $user, 'device' => $device];
}

/**
 * Create a device token for the given user. Returns plaintext token once.
 *
 * @return array{token: string, device: array}
 */
function gos_create_device(int $userId, string $deviceName = 'Android'): array
{
    $raw = gos_device_token_prefix() . bin2hex(random_bytes(24));
    $hash = hash('sha256', $raw);
    $prefix = substr($raw, 0, 12);
    $name = trim($deviceName) !== '' ? trim($deviceName) : 'Android';
    $pdo = gos_pdo();
    $stmt = $pdo->prepare(
        'INSERT INTO grokify_devices (user_id, device_name, token_hash, token_prefix) VALUES (?, ?, ?, ?)'
    );
    $stmt->execute([$userId, $name, $hash, $prefix]);
    $id = (int) $pdo->lastInsertId();
    $stmt = $pdo->prepare('SELECT * FROM grokify_devices WHERE id = ?');
    $stmt->execute([$id]);
    $device = $stmt->fetch();
    if (!is_array($device)) {
        throw new RuntimeException('device_create_failed');
    }
    return ['token' => $raw, 'device' => $device];
}

/**
 * @return array<string, mixed>|null
 */
function gos_device_by_id(int $id): ?array
{
    if ($id <= 0 || !gos_table_exists('grokify_devices')) {
        return null;
    }
    $stmt = gos_pdo()->prepare('SELECT * FROM grokify_devices WHERE id = ? LIMIT 1');
    $stmt->execute([$id]);
    $row = $stmt->fetch();
    return is_array($row) ? $row : null;
}

function gos_touch_device(int $deviceId, ?string $versionName = null, ?int $versionCode = null): void
{
    if ($deviceId <= 0 || !gos_table_exists('grokify_devices')) {
        return;
    }
    $ip = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
    $stmt = gos_pdo()->prepare(
        'UPDATE grokify_devices
         SET last_seen_at = NOW(),
             last_ip = COALESCE(?, last_ip),
             app_version_name = COALESCE(?, app_version_name),
             app_version_code = COALESCE(?, app_version_code)
         WHERE id = ?'
    );
    $stmt->execute([
        $ip !== '' ? $ip : null,
        $versionName !== null && $versionName !== '' ? $versionName : null,
        $versionCode !== null && $versionCode > 0 ? $versionCode : null,
        $deviceId,
    ]);
}

/**
 * Sanitize a single notification item from the Android client.
 *
 * @param array<string, mixed> $raw
 * @return array<string, mixed>|null
 */
function gos_sanitize_notification_item(array $raw): ?array
{
    $package = trim((string) ($raw['package'] ?? $raw['pkg'] ?? ''));
    $title = trim((string) ($raw['title'] ?? ''));
    $text = trim((string) ($raw['text'] ?? $raw['body'] ?? ''));
    if ($package === '' && $title === '' && $text === '') {
        return null;
    }
    $key = trim((string) ($raw['key'] ?? ''));
    if ($key === '') {
        $key = $package . '|' . (string) ($raw['id'] ?? '') . '|' . (string) ($raw['tag'] ?? '');
    }
    $postTime = (int) ($raw['post_time'] ?? $raw['postTime'] ?? 0);
    if ($postTime > 1_000_000_000_000) {
        // ms → seconds
        $postTime = (int) floor($postTime / 1000);
    }

    return [
        'key' => mb_substr($key, 0, 191),
        'package' => mb_substr($package, 0, 191),
        'app_label' => mb_substr(trim((string) ($raw['app_label'] ?? $raw['app'] ?? '')), 0, 128),
        'title' => mb_substr($title, 0, 512),
        'text' => mb_substr($text, 0, 2000),
        'sub_text' => mb_substr(trim((string) ($raw['sub_text'] ?? $raw['subText'] ?? '')), 0, 512),
        'category' => mb_substr(trim((string) ($raw['category'] ?? '')), 0, 64),
        'is_ongoing' => !empty($raw['is_ongoing']) || !empty($raw['ongoing']),
        'post_time' => $postTime > 0 ? $postTime : null,
    ];
}

/**
 * Store the latest active-notification snapshot for a device (meta + device_events).
 *
 * @param list<array<string, mixed>> $items
 * @param array<string, mixed> $extra Optional client diagnostics (access_granted, listener_bound, …)
 * @return array{count: int, updated_at: string}
 */
function gos_device_save_notifications(int $deviceId, int $userId, array $items, array $extra = []): array
{
    if ($deviceId < 1 || $userId < 1 || !gos_table_exists('grokify_devices')) {
        throw new RuntimeException('devices_unavailable');
    }

    $clean = [];
    foreach ($items as $raw) {
        if (!is_array($raw)) {
            continue;
        }
        $item = gos_sanitize_notification_item($raw);
        if ($item === null) {
            continue;
        }
        $clean[] = $item;
        if (count($clean) >= 80) {
            break;
        }
    }

    $now = gmdate('c');
    $snapshot = [
        'notifications' => $clean,
        'count' => count($clean),
        'updated_at' => $now,
    ];
    if (array_key_exists('access_granted', $extra)) {
        $snapshot['access_granted'] = (bool) $extra['access_granted'];
    }
    if (array_key_exists('listener_bound', $extra)) {
        $snapshot['listener_bound'] = (bool) $extra['listener_bound'];
    }

    $pdo = gos_pdo();
    $st = $pdo->prepare('SELECT meta FROM grokify_devices WHERE id = ? AND user_id = ? LIMIT 1');
    $st->execute([$deviceId, $userId]);
    $row = $st->fetch(PDO::FETCH_ASSOC);
    if (!is_array($row)) {
        throw new RuntimeException('device_not_found');
    }

    $meta = [];
    if (!empty($row['meta'])) {
        $decoded = json_decode((string) $row['meta'], true);
        if (is_array($decoded)) {
            $meta = $decoded;
        }
    }
    $meta['notification_snapshot'] = $snapshot;

    $upd = $pdo->prepare(
        'UPDATE grokify_devices
         SET meta = ?, last_seen_at = NOW(), last_ip = COALESCE(?, last_ip)
         WHERE id = ? AND user_id = ?'
    );
    $ip = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
    $upd->execute([
        json_encode($meta, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        $ip !== '' ? $ip : null,
        $deviceId,
        $userId,
    ]);

    if (gos_table_exists('grokify_device_events')) {
        $ev = $pdo->prepare(
            'INSERT INTO grokify_device_events (device_id, user_id, event_type, payload)
             VALUES (?, ?, ?, ?)'
        );
        $ev->execute([
            $deviceId,
            $userId,
            'notification_snapshot',
            json_encode($snapshot, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        ]);
        // Keep event log bounded per device
        $pdo->prepare(
            'DELETE FROM grokify_device_events
             WHERE device_id = ? AND event_type = ?
               AND id NOT IN (
                 SELECT id FROM (
                   SELECT id FROM grokify_device_events
                   WHERE device_id = ? AND event_type = ?
                   ORDER BY id DESC LIMIT 30
                 ) keep_rows
               )'
        )->execute([$deviceId, 'notification_snapshot', $deviceId, 'notification_snapshot']);
    }

    return ['count' => count($clean), 'updated_at' => $now];
}

/**
 * Latest notification snapshots for a user's active devices.
 *
 * @return list<array{device_id: int, device_name: string, count: int, updated_at: ?string, notifications: list<array>}>
 */
function gos_user_notification_snapshots(int $userId): array
{
    if ($userId < 1 || !gos_table_exists('grokify_devices')) {
        return [];
    }
    $st = gos_pdo()->prepare(
        'SELECT id, device_name, meta, last_seen_at
         FROM grokify_devices
         WHERE user_id = ? AND revoked_at IS NULL
         ORDER BY last_seen_at DESC, id DESC'
    );
    $st->execute([$userId]);
    $out = [];
    while ($row = $st->fetch(PDO::FETCH_ASSOC)) {
        if (!is_array($row)) {
            continue;
        }
        $meta = [];
        if (!empty($row['meta'])) {
            $decoded = json_decode((string) $row['meta'], true);
            if (is_array($decoded)) {
                $meta = $decoded;
            }
        }
        $snap = $meta['notification_snapshot'] ?? null;
        if (!is_array($snap)) {
            continue;
        }
        $notes = $snap['notifications'] ?? [];
        if (!is_array($notes)) {
            $notes = [];
        }
        $out[] = [
            'device_id' => (int) $row['id'],
            'device_name' => (string) $row['device_name'],
            'count' => (int) ($snap['count'] ?? count($notes)),
            'updated_at' => isset($snap['updated_at']) ? (string) $snap['updated_at'] : null,
            'last_seen_at' => $row['last_seen_at'] ?? null,
            'notifications' => array_values($notes),
        ];
    }

    return $out;
}

/**
 * Flatten snapshots into short plain-text lines for agent notes / prompt context.
 *
 * @param list<array<string, mixed>> $snapshots
 * @return list<string>
 */
function gos_notification_note_lines(array $snapshots, int $maxLines = 40): array
{
    $lines = [];
    foreach ($snapshots as $snap) {
        if (!is_array($snap)) {
            continue;
        }
        $device = trim((string) ($snap['device_name'] ?? 'Device'));
        $updated = trim((string) ($snap['updated_at'] ?? ''));
        $header = "Phone notifications on {$device}";
        if ($updated !== '') {
            $header .= " (synced {$updated})";
        }
        $header .= ':';
        $items = $snap['notifications'] ?? [];
        if (!is_array($items) || $items === []) {
            $lines[] = $header . ' (none active)';
            continue;
        }
        $lines[] = $header;
        foreach ($items as $n) {
            if (!is_array($n)) {
                continue;
            }
            $app = trim((string) ($n['app_label'] ?? ''));
            if ($app === '') {
                $app = trim((string) ($n['package'] ?? 'app'));
            }
            $title = trim((string) ($n['title'] ?? ''));
            $text = trim((string) ($n['text'] ?? ''));
            $body = $title;
            if ($text !== '' && $text !== $title) {
                $body = $title === '' ? $text : "{$title}: {$text}";
            }
            if ($body === '') {
                continue;
            }
            $lines[] = "[{$app}] {$body}";
            if (count($lines) >= $maxLines) {
                $lines[] = '…(truncated)';
                return $lines;
            }
        }
    }

    return $lines;
}

