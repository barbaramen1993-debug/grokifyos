<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$user = $access['user'];
$userId = (int) $user['id'];

$grokModels = [];
$defaultModel = 'gb:grok-4.6';

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
            $modelId = 'gb:' . $id;
            $bridgeEfforts = $m['reasoning_efforts'] ?? null;
            $efforts = is_array($bridgeEfforts) && $bridgeEfforts !== []
                ? array_values(array_filter(array_map('strval', $bridgeEfforts)))
                : gos_reasoning_efforts_for_model($modelId);
            $bridgeDefaultEffort = trim((string) ($m['default_reasoning_effort'] ?? ''));
            $grokModels[] = [
                'id' => $modelId,
                'name' => (string) ($m['name'] ?? $id),
                'provider' => 'grok-build',
                'reasoning_efforts' => $efforts,
                'default_reasoning_effort' => $bridgeDefaultEffort !== ''
                    ? gos_clamp_reasoning_effort($modelId, $bridgeDefaultEffort)
                    : gos_default_reasoning_effort_for_model($modelId),
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
    $fallbackIds = ['gb:grok-4.6', 'gb:grok-4.5', 'gb:grok-composer-2.5-fast'];
    foreach ($fallbackIds as $fid) {
        $grokModels[] = [
            'id' => $fid,
            'name' => substr($fid, 3),
            'provider' => 'grok-build',
            'reasoning_efforts' => gos_reasoning_efforts_for_model($fid),
            'default_reasoning_effort' => gos_default_reasoning_effort_for_model($fid),
        ];
    }
}

$models = $grokModels;
$modelIds = array_column($models, 'id');
if (!in_array($defaultModel, $modelIds, true)) {
    $defaultModel = $modelIds[0] ?? 'gb:grok-4.6';
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
    $effortIn = trim((string) ($body['reasoning_effort'] ?? $body['effort'] ?? ''));
    $effortModel = gos_system_chat_selected_model();
    if ($effortModel === '' || !in_array($effortModel, $modelIds, true)) {
        $effortModel = $defaultModel;
    }
    if ($effortIn !== '') {
        gos_system_chat_set_selected_reasoning_effort($effortIn, $effortModel);
        gos_system_chat_audit('info', 'access', 'Reasoning effort saved', [
            'model' => $effortModel,
            'reasoning_effort' => gos_system_chat_selected_reasoning_effort(),
        ], $userId);
    } else {
        // Model switch without an effort: drop xhigh (etc.) if the new model cannot use it.
        $clamped = gos_clamp_reasoning_effort($effortModel, gos_system_chat_selected_reasoning_effort());
        if ($clamped !== gos_system_chat_selected_reasoning_effort()) {
            gos_system_chat_set_selected_reasoning_effort($clamped, $effortModel);
        }
    }
}

$selected = gos_system_chat_selected_model();
if ($selected === '' || $selected === 'auto' || !in_array($selected, $modelIds, true)) {
    $selected = $defaultModel;
    if (gos_system_chat_selected_model() !== $selected) {
        gos_system_chat_set_selected_model($selected);
    }
}

foreach ($models as &$modelRow) {
    $mid = (string) ($modelRow['id'] ?? '');
    if ($mid === '') {
        continue;
    }
    if (!isset($modelRow['reasoning_efforts']) || !is_array($modelRow['reasoning_efforts']) || $modelRow['reasoning_efforts'] === []) {
        $modelRow['reasoning_efforts'] = gos_reasoning_efforts_for_model($mid);
    }
    if (trim((string) ($modelRow['default_reasoning_effort'] ?? '')) === '') {
        $modelRow['default_reasoning_effort'] = gos_default_reasoning_effort_for_model($mid);
    }
}
unset($modelRow);

$selectedEffort = gos_clamp_reasoning_effort($selected, gos_system_chat_selected_reasoning_effort());
if ($selectedEffort !== gos_system_chat_selected_reasoning_effort()) {
    gos_system_chat_set_selected_reasoning_effort($selectedEffort, $selected);
}

gos_system_chat_audit('info', 'access', 'Models listed', [
    'grok_count' => count($grokModels),
], $userId);

gos_api_json([
    'ok' => true,
    'models' => $models,
    'selected' => $selected,
    'default_model' => $defaultModel,
    'selected_reasoning_effort' => $selectedEffort,
    'ws_token' => gos_system_chat_ws_token($user),
    'ws_path' => gos_system_chat_ws_path(),
    'bridge_healthy' => $bridgeResp !== false,
]);
