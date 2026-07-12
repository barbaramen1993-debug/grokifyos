<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$user = $access['user'];
$userId = (int) $user['id'];

$grokModels = [];
$defaultModel = 'gb:grok-4.5';

$bridgeUrl = gos_system_chat_bridge_url() . '/models';
$ctx = stream_context_create(['http' => ['timeout' => 5, 'method' => 'GET']]);
$bridgeResp = @file_get_contents($bridgeUrl, false, $ctx);
if ($bridgeResp !== false) {
    $bridgeData = json_decode($bridgeResp, true);
    if (is_array($bridgeData)) {
        foreach ($bridgeData['grok_models'] ?? [] as $m) {
            $id = (string) ($m['id'] ?? '');
            if ($id === '') {
                continue;
            }
            $grokModels[] = [
                'id' => 'gb:' . $id,
                'name' => (string) ($m['name'] ?? $id),
                'provider' => 'grok-build',
            ];
        }
        $bridgeDefault = (string) ($bridgeData['default_model'] ?? '');
        if ($bridgeDefault !== '' && str_starts_with($bridgeDefault, 'gb:')) {
            $defaultModel = $bridgeDefault;
        }
    }
}

if ($grokModels === []) {
    // Offline fallback so the picker still works when the bridge is briefly down
    // (real model ids only — no synthetic chat content).
    $grokModels = [
        ['id' => 'gb:grok-4.5', 'name' => 'grok-4.5', 'provider' => 'grok-build'],
        ['id' => 'gb:grok-composer-2.5-fast', 'name' => 'grok-composer-2.5-fast', 'provider' => 'grok-build'],
    ];
}

$models = $grokModels;
$modelIds = array_column($models, 'id');
if (!in_array($defaultModel, $modelIds, true)) {
    $defaultModel = $modelIds[0] ?? 'gb:grok-4.5';
}

if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    $body = gos_json_body();
    $model = trim((string) ($body['model'] ?? ''));
    if ($model !== '') {
        if (!str_starts_with($model, 'gb:') && !str_starts_with($model, 'grok:')) {
            $model = $defaultModel;
        } elseif (str_starts_with($model, 'grok:') && !str_starts_with($model, 'gb:')) {
            $model = 'gb:' . substr($model, 5);
        }
        if (!in_array($model, $modelIds, true)) {
            $model = $defaultModel;
        }
        gos_system_chat_set_selected_model($model);
        gos_system_chat_audit('info', 'access', 'Model preference saved', ['model' => $model], $userId);
    }
}

$selected = gos_system_chat_selected_model();
if ($selected === '' || $selected === 'auto' || !in_array($selected, $modelIds, true)) {
    $selected = $defaultModel;
    if (gos_system_chat_selected_model() !== $selected) {
        gos_system_chat_set_selected_model($selected);
    }
}

gos_system_chat_audit('info', 'access', 'Models listed', [
    'grok_count' => count($grokModels),
], $userId);

gos_api_json([
    'ok' => true,
    'models' => $models,
    'selected' => $selected,
    'default_model' => $defaultModel,
    'ws_token' => gos_system_chat_ws_token($user),
    'ws_path' => gos_system_chat_ws_path(),
    'bridge_healthy' => $bridgeResp !== false,
]);
