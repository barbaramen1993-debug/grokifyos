<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

/**
 * Plugin marketplace catalog.
 *
 * GET  → list plugins (builtin host modules + remote script packages)
 * Auth: device Bearer or session (same as /update.php)
 */
gos_require_method('GET');
$access = gos_require_access();

if (!empty($access['device'])) {
    $vName = isset($_GET['version_name']) ? (string) $_GET['version_name'] : null;
    $vCode = (int) ($_GET['version_code'] ?? $_GET['versionCode'] ?? 0);
    gos_touch_device((int) $access['device']['id'], $vName, $vCode > 0 ? $vCode : null);
}

$catalogPath = gos_root() . '/storage/plugins/catalog.json';
$site = rtrim(gos_site_url(), '/');

$catalog = [
    'version' => 1,
    'updated_at' => null,
    'plugins' => [],
];

if (is_readable($catalogPath)) {
    $raw = file_get_contents($catalogPath);
    $decoded = is_string($raw) ? json_decode($raw, true) : null;
    if (is_array($decoded)) {
        $catalog['version'] = (int) ($decoded['version'] ?? 1);
        $catalog['updated_at'] = $decoded['updated_at'] ?? null;
        $list = $decoded['plugins'] ?? [];
        if (is_array($list)) {
            $catalog['plugins'] = $list;
        }
    }
}

$out = [];
foreach ($catalog['plugins'] as $p) {
    if (!is_array($p)) {
        continue;
    }
    if (array_key_exists('enabled', $p) && !$p['enabled']) {
        continue;
    }
    $id = trim((string) ($p['id'] ?? ''));
    if ($id === '') {
        continue;
    }
    $kind = strtolower(trim((string) ($p['kind'] ?? 'host_module')));
    if ($kind !== 'host_module' && $kind !== 'webview') {
        $kind = 'host_module';
    }

    $requiredKeys = [];
    $rk = $p['required_keys'] ?? [];
    if (is_array($rk)) {
        foreach ($rk as $k) {
            if (is_string($k) && trim($k) !== '') {
                $requiredKeys[] = [
                    'id' => trim($k),
                    'label' => trim($k),
                    'description' => '',
                    'required' => true,
                ];
                continue;
            }
            if (!is_array($k)) {
                continue;
            }
            $kid = trim((string) ($k['id'] ?? ''));
            if ($kid === '') {
                continue;
            }
            $requiredKeys[] = [
                'id' => $kid,
                'label' => (string) ($k['label'] ?? $kid),
                'description' => (string) ($k['description'] ?? $k['hint'] ?? ''),
                'required' => array_key_exists('required', $k) ? (bool) $k['required'] : true,
            ];
        }
    }

    $item = [
        'id' => $id,
        'title' => (string) ($p['title'] ?? $id),
        'subtitle' => (string) ($p['subtitle'] ?? ''),
        'version' => (string) ($p['version'] ?? '1.0.0'),
        'author' => (string) ($p['author'] ?? 'GrokifyOS'),
        'kind' => $kind,
        'capabilities' => array_values(array_filter(array_map('strval', (array) ($p['capabilities'] ?? [])))),
        'accent' => strtolower((string) ($p['accent'] ?? 'cyan')),
        'icon' => strtolower((string) ($p['icon'] ?? 'apps')),
        'featured' => !empty($p['featured']),
        'source' => $kind === 'webview' ? 'remote' : 'builtin',
        'required_keys' => $requiredKeys,
    ];

    if ($kind === 'host_module') {
        $hostId = trim((string) ($p['host_module_id'] ?? $id));
        $item['host_module_id'] = $hostId !== '' ? $hostId : $id;
        $item['package_url'] = null;
    } else {
        $pkg = trim((string) ($p['package'] ?? $id));
        if ($pkg === '') {
            $pkg = $id;
        }
        $item['host_module_id'] = null;
        $item['package'] = $pkg;
        $item['package_url'] = $site . '/api/plugin-package.php?id=' . rawurlencode($pkg);
    }

    $out[] = $item;
}

gos_api_json([
    'ok' => true,
    'catalog_version' => $catalog['version'],
    'updated_at' => $catalog['updated_at'],
    'source' => 'server',
    'count' => count($out),
    'plugins' => $out,
]);
