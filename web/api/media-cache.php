<?php

declare(strict_types=1);

/**
 * Content-addressed media cache for durable album/artist art (and similar).
 *
 * POST (auth required — device token or session):
 *   JSON  { "url": "https://i.scdn.co/..." }
 *     Server downloads once, stores by content hash, returns durable URL.
 *   Raw body with Content-Type: image/* (or application/octet-stream)
 *     Optional query ?source=original-url for de-dupe by source.
 *
 * GET (public):
 *   ?id=<hex>  → serve the cached file with long-lived cache headers.
 *
 * Files live under uploads/media-cache/{sha256}.{ext}
 */

require_once __DIR__ . '/_common.php';

$method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');

if ($method === 'GET') {
    $id = preg_replace('/[^a-f0-9]/i', '', (string) ($_GET['id'] ?? ''));
    if ($id === '' || strlen($id) < 16) {
        gos_api_json(['ok' => false, 'error' => 'id_required'], 400);
    }
    $file = gos_media_cache_find($id);
    if ($file === null) {
        gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
    }
    $mime = gos_media_cache_mime($file);
    // Drop JSON Content-Type from _common.php before binary response.
    header_remove('Content-Type');
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . (string) filesize($file));
    header('Cache-Control: public, max-age=31536000, immutable');
    header('X-Content-Type-Options: nosniff');
    readfile($file);
    exit;
}

if ($method !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}

// Upload / mirror requires device or session auth.
$access = gos_require_access();

$dir = gos_media_cache_dir();
if (!is_dir($dir) && !mkdir($dir, 0775, true) && !is_dir($dir)) {
    gos_api_json(['ok' => false, 'error' => 'mkdir_failed'], 500);
}

$ct = strtolower(trim(explode(';', (string) ($_SERVER['CONTENT_TYPE'] ?? ''))[0]));
$sourceUrl = '';
$bytes = null;

if ($ct === 'application/json' || $ct === 'text/json' || $ct === '') {
    $body = gos_json_body();
    $sourceUrl = trim((string) ($body['url'] ?? $body['source'] ?? ''));
    $b64 = (string) ($body['data'] ?? $body['bytes'] ?? '');
    if ($b64 !== '') {
        // Optional data URL or raw base64.
        if (str_contains($b64, ',')) {
            $b64 = substr($b64, strpos($b64, ',') + 1);
        }
        $decoded = base64_decode($b64, true);
        if ($decoded === false || $decoded === '') {
            gos_api_json(['ok' => false, 'error' => 'invalid_base64'], 400);
        }
        $bytes = $decoded;
        if ($ct === '' || $ct === 'application/json') {
            $ct = trim((string) ($body['content_type'] ?? $body['mime'] ?? 'image/jpeg'));
        }
    }
} else {
    // Raw image body.
    $raw = file_get_contents('php://input');
    if ($raw !== false && $raw !== '') {
        $bytes = $raw;
    }
    $sourceUrl = trim((string) ($_GET['source'] ?? $_GET['url'] ?? ''));
}

// Prefer source-url de-dupe when we already mirrored this CDN URL.
if ($sourceUrl !== '' && $bytes === null) {
    $bySource = gos_media_cache_by_source($sourceUrl);
    if ($bySource !== null) {
        gos_api_json([
            'ok' => true,
            'cached' => true,
            'id' => $bySource['id'],
            'url' => $bySource['url'],
            'content_type' => $bySource['content_type'],
        ]);
    }
}

if ($bytes === null && $sourceUrl !== '') {
    if (!gos_media_cache_url_allowed($sourceUrl)) {
        gos_api_json(['ok' => false, 'error' => 'url_not_allowed'], 400);
    }
    try {
        $fetched = gos_media_cache_download($sourceUrl);
    } catch (Throwable $e) {
        gos_api_json([
            'ok' => false,
            'error' => 'download_failed',
            'message' => $e->getMessage(),
        ], 502);
    }
    $bytes = $fetched['bytes'];
    $ct = $fetched['content_type'] ?: $ct;
}

if ($bytes === null || $bytes === '') {
    gos_api_json(['ok' => false, 'error' => 'body_or_url_required'], 400);
}

$max = 5 * 1024 * 1024;
if (strlen($bytes) > $max) {
    gos_api_json(['ok' => false, 'error' => 'too_large', 'max' => $max], 413);
}

// Sniff real image type; reject non-images.
$finfo = new finfo(FILEINFO_MIME_TYPE);
$detected = $finfo->buffer($bytes) ?: '';
if (!str_starts_with($detected, 'image/')) {
    // Allow octet-stream if extension sniff still looks like an image.
    if (!gos_media_cache_looks_like_image($bytes)) {
        gos_api_json(['ok' => false, 'error' => 'not_an_image', 'detected' => $detected], 415);
    }
    $detected = 'image/jpeg';
}
$mime = $detected !== '' ? $detected : (str_starts_with($ct, 'image/') ? $ct : 'image/jpeg');
$ext = gos_media_cache_ext($mime);

$id = hash('sha256', $bytes);
$path = $dir . '/' . $id . '.' . $ext;
$existed = is_file($path);
if (!$existed) {
    $tmp = $path . '.part.' . getmypid();
    if (file_put_contents($tmp, $bytes) === false) {
        gos_api_json(['ok' => false, 'error' => 'write_failed'], 500);
    }
    if (!@rename($tmp, $path)) {
        @unlink($tmp);
        if (!is_file($path)) {
            gos_api_json(['ok' => false, 'error' => 'rename_failed'], 500);
        }
    }
    @chmod($path, 0644);
    try {
        @chown($path, 'www-data');
    } catch (Throwable $e) {
        // best-effort on hosts without chown rights
    }
}

if ($sourceUrl !== '') {
    gos_media_cache_remember_source($sourceUrl, $id, $mime);
}

$public = gos_media_cache_public_url($id);
gos_api_json([
    'ok' => true,
    'cached' => $existed,
    'id' => $id,
    'url' => $public,
    'content_type' => $mime,
    'bytes' => strlen($bytes),
    'auth' => $access['auth'],
]);

// ── helpers ──────────────────────────────────────────────────────────

function gos_media_cache_dir(): string
{
    $root = gos_root();
    return $root . '/uploads/media-cache';
}

function gos_media_cache_public_url(string $id): string
{
    return rtrim(gos_site_url(), '/') . '/api/media-cache.php?id=' . rawurlencode($id);
}

/** @return array{id:string,url:string,content_type:string}|null */
function gos_media_cache_by_source(string $url): ?array
{
    $map = gos_media_cache_source_map_path();
    if (!is_file($map)) {
        return null;
    }
    $raw = @file_get_contents($map);
    if ($raw === false || $raw === '') {
        return null;
    }
    $data = json_decode($raw, true);
    if (!is_array($data)) {
        return null;
    }
    $key = hash('sha256', $url);
    $entry = $data[$key] ?? null;
    if (!is_array($entry)) {
        return null;
    }
    $id = (string) ($entry['id'] ?? '');
    if ($id === '' || gos_media_cache_find($id) === null) {
        return null;
    }
    return [
        'id' => $id,
        'url' => gos_media_cache_public_url($id),
        'content_type' => (string) ($entry['content_type'] ?? 'image/jpeg'),
    ];
}

function gos_media_cache_remember_source(string $url, string $id, string $mime): void
{
    $map = gos_media_cache_source_map_path();
    $data = [];
    if (is_file($map)) {
        $raw = @file_get_contents($map);
        if (is_string($raw) && $raw !== '') {
            $decoded = json_decode($raw, true);
            if (is_array($decoded)) {
                $data = $decoded;
            }
        }
    }
    $key = hash('sha256', $url);
    $data[$key] = [
        'id' => $id,
        'source' => $url,
        'content_type' => $mime,
        'at' => time(),
    ];
    // Cap map size so it cannot grow forever (keep newest ~4000).
    if (count($data) > 4000) {
        uasort($data, static fn ($a, $b) => (int) ($b['at'] ?? 0) <=> (int) ($a['at'] ?? 0));
        $data = array_slice($data, 0, 3500, true);
    }
    $tmp = $map . '.part.' . getmypid();
    file_put_contents($tmp, json_encode($data, JSON_UNESCAPED_SLASHES));
    @rename($tmp, $map);
    @chmod($map, 0644);
}

function gos_media_cache_source_map_path(): string
{
    return gos_media_cache_dir() . '/_source_map.json';
}

function gos_media_cache_find(string $id): ?string
{
    $dir = gos_media_cache_dir();
    foreach (['jpg', 'jpeg', 'png', 'webp', 'gif', 'bin'] as $ext) {
        $p = $dir . '/' . $id . '.' . $ext;
        if (is_file($p) && is_readable($p)) {
            return $p;
        }
    }
    // Glob fallback (rare).
    $matches = glob($dir . '/' . $id . '.*') ?: [];
    foreach ($matches as $m) {
        if (is_file($m) && !str_ends_with($m, '.part') && !str_contains(basename($m), '_source')) {
            return $m;
        }
    }
    return null;
}

function gos_media_cache_mime(string $file): string
{
    $ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
    return match ($ext) {
        'png' => 'image/png',
        'webp' => 'image/webp',
        'gif' => 'image/gif',
        'jpg', 'jpeg' => 'image/jpeg',
        default => (new finfo(FILEINFO_MIME_TYPE))->file($file) ?: 'application/octet-stream',
    };
}

function gos_media_cache_ext(string $mime): string
{
    $m = strtolower($mime);
    return match (true) {
        str_contains($m, 'png') => 'png',
        str_contains($m, 'webp') => 'webp',
        str_contains($m, 'gif') => 'gif',
        default => 'jpg',
    };
}

function gos_media_cache_url_allowed(string $url): bool
{
    $p = parse_url($url);
    if (!is_array($p)) {
        return false;
    }
    $scheme = strtolower((string) ($p['scheme'] ?? ''));
    if ($scheme !== 'https' && $scheme !== 'http') {
        return false;
    }
    $host = strtolower((string) ($p['host'] ?? ''));
    if ($host === '') {
        return false;
    }
    // Spotify + common CDNs we use for cover/artist art.
    $ok = [
        'i.scdn.co',
        'mosaic.scdn.co',
        'image-cdn-ak.spotifycdn.com',
        'image-cdn-fa.spotifycdn.com',
        'i.imgur.com',
    ];
    foreach ($ok as $h) {
        if ($host === $h || str_ends_with($host, '.' . $h)) {
            return true;
        }
    }
    if (str_ends_with($host, '.scdn.co') || str_ends_with($host, '.spotifycdn.com')) {
        return true;
    }
    // Also allow re-upload of our own cached URLs (no-op path).
    $siteHost = parse_url(gos_site_url(), PHP_URL_HOST);
    if (is_string($siteHost) && strtolower($siteHost) === $host) {
        return true;
    }
    return false;
}

/** @return array{bytes:string,content_type:string} */
function gos_media_cache_download(string $url): array
{
    if (!function_exists('curl_init')) {
        throw new RuntimeException('curl_missing');
    }
    $ch = curl_init($url);
    if ($ch === false) {
        throw new RuntimeException('curl_init_failed');
    }
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 5,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT => 20,
        CURLOPT_USERAGENT => 'GrokifyOS-MediaCache/1.0',
        CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
        CURLOPT_REDIR_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
        CURLOPT_SSL_VERIFYPEER => true,
    ]);
    $body = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $ctype = (string) curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
    $err = curl_error($ch);
    curl_close($ch);
    if ($body === false || $body === '') {
        throw new RuntimeException($err !== '' ? $err : 'empty_body');
    }
    if ($code < 200 || $code >= 300) {
        throw new RuntimeException('HTTP ' . $code);
    }
    $mime = strtolower(trim(explode(';', $ctype)[0]));
    return ['bytes' => $body, 'content_type' => $mime];
}

function gos_media_cache_looks_like_image(string $bytes): bool
{
    if (strlen($bytes) < 12) {
        return false;
    }
    $sig = substr($bytes, 0, 12);
    // JPEG
    if (str_starts_with($sig, "\xFF\xD8\xFF")) {
        return true;
    }
    // PNG
    if (str_starts_with($sig, "\x89PNG\r\n\x1A\n")) {
        return true;
    }
    // GIF
    if (str_starts_with($sig, 'GIF87a') || str_starts_with($sig, 'GIF89a')) {
        return true;
    }
    // WEBP (RIFF....WEBP)
    if (str_starts_with($sig, 'RIFF') && substr($sig, 8, 4) === 'WEBP') {
        return true;
    }
    return false;
}
