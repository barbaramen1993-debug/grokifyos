<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$userId = (int) $access['user']['id'];
gos_require_method('POST');
$body = gos_json_body();
$action = (string) ($body['action'] ?? 'list');

if ($action === 'list') {
    $st = gos_pdo()->query(
        'SELECT id, note_text, enabled, created_by, created_at, updated_at
         FROM system_chat_notes ORDER BY id ASC'
    );
    gos_api_json(['ok' => true, 'notes' => $st ? ($st->fetchAll(PDO::FETCH_ASSOC) ?: []) : []]);
}

if ($action === 'create') {
    $text = mb_substr(trim((string) ($body['note_text'] ?? '')), 0, 500);
    if ($text === '') {
        gos_api_json(['ok' => false, 'error' => 'validation'], 400);
    }
    $st = gos_pdo()->prepare(
        'INSERT INTO system_chat_notes (note_text, enabled, created_by) VALUES (?, 1, ?)'
    );
    $st->execute([$text, $userId]);
    $id = (int) gos_pdo()->lastInsertId();
    gos_system_chat_audit('info', 'access', 'Note created', ['note_id' => $id], $userId);
    gos_api_json(['ok' => true, 'id' => $id]);
}

if ($action === 'edit') {
    $id = (int) ($body['note_id'] ?? 0);
    $text = mb_substr(trim((string) ($body['note_text'] ?? '')), 0, 500);
    if ($id < 1 || $text === '') {
        gos_api_json(['ok' => false, 'error' => 'validation'], 400);
    }
    gos_pdo()->prepare('UPDATE system_chat_notes SET note_text = ? WHERE id = ?')->execute([$text, $id]);
    gos_system_chat_audit('info', 'access', 'Note edited', ['note_id' => $id], $userId);
    gos_api_json(['ok' => true]);
}

if ($action === 'toggle') {
    $id = (int) ($body['note_id'] ?? 0);
    $enabled = !empty($body['enabled']) ? 1 : 0;
    if ($id < 1) {
        gos_api_json(['ok' => false, 'error' => 'validation'], 400);
    }
    gos_pdo()->prepare('UPDATE system_chat_notes SET enabled = ? WHERE id = ?')->execute([$enabled, $id]);
    gos_system_chat_audit('info', 'access', 'Note toggled', ['note_id' => $id, 'enabled' => $enabled], $userId);
    gos_api_json(['ok' => true]);
}

if ($action === 'delete') {
    $id = (int) ($body['note_id'] ?? 0);
    if ($id < 1) {
        gos_api_json(['ok' => false, 'error' => 'validation'], 400);
    }
    gos_pdo()->prepare('DELETE FROM system_chat_notes WHERE id = ?')->execute([$id]);
    gos_system_chat_audit('info', 'access', 'Note deleted', ['note_id' => $id], $userId);
    gos_api_json(['ok' => true]);
}

gos_api_json(['ok' => false, 'error' => 'unknown_action'], 400);
