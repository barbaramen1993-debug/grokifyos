<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$userId = (int) $access['user']['id'];
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    $sessionId = (string) ($_GET['session_id'] ?? '');
    if (!gos_system_chat_session_owned($sessionId, $userId)) {
        gos_api_json([
            'ok' => true,
            'messages' => [],
            'has_more' => false,
            'total' => 0,
            'oldest_id' => 0,
            'newest_id' => 0,
        ]);
    }
    gos_system_chat_audit('info', 'access', 'Messages loaded', [
        'session_id' => $sessionId,
    ], $userId, $sessionId);

    $limitRaw = $_GET['limit'] ?? null;
    $beforeId = max(0, (int) ($_GET['before_id'] ?? 0));
    $usePaging = $limitRaw !== null && $limitRaw !== '';
    $limit = $usePaging ? min(100, max(1, (int) $limitRaw)) : 0;

    $countSt = gos_pdo()->prepare(
        'SELECT COUNT(*) FROM system_chat_messages WHERE session_id = ?'
    );
    $countSt->execute([$sessionId]);
    $total = (int) $countSt->fetchColumn();

    if ($usePaging) {
        if ($beforeId > 0) {
            $st = gos_pdo()->prepare(
                'SELECT id, role, content, metadata, input_tokens, output_tokens, excluded_from_context, created_at
                 FROM system_chat_messages
                 WHERE session_id = ? AND id < ?
                 ORDER BY id DESC
                 LIMIT ' . (int) $limit
            );
            $st->execute([$sessionId, $beforeId]);
        } else {
            $st = gos_pdo()->prepare(
                'SELECT id, role, content, metadata, input_tokens, output_tokens, excluded_from_context, created_at
                 FROM system_chat_messages
                 WHERE session_id = ?
                 ORDER BY id DESC
                 LIMIT ' . (int) $limit
            );
            $st->execute([$sessionId]);
        }
        $messages = $st->fetchAll(PDO::FETCH_ASSOC) ?: [];
        $messages = array_reverse($messages);
    } else {
        $st = gos_pdo()->prepare(
            'SELECT id, role, content, metadata, input_tokens, output_tokens, excluded_from_context, created_at
             FROM system_chat_messages WHERE session_id = ? ORDER BY created_at ASC, id ASC'
        );
        $st->execute([$sessionId]);
        $messages = $st->fetchAll(PDO::FETCH_ASSOC) ?: [];
    }

    foreach ($messages as &$m) {
        if (!empty($m['metadata']) && is_string($m['metadata'])) {
            $m['metadata'] = json_decode($m['metadata'], true);
        }
        $m['excluded_from_context'] = (int) ($m['excluded_from_context'] ?? 0);
        $m['id'] = (int) ($m['id'] ?? 0);
    }
    unset($m);

    $oldestId = 0;
    $newestId = 0;
    if ($messages !== []) {
        $oldestId = (int) $messages[0]['id'];
        $newestId = (int) $messages[count($messages) - 1]['id'];
    }
    $hasMore = false;
    if ($usePaging && $oldestId > 0) {
        $moreSt = gos_pdo()->prepare(
            'SELECT 1 FROM system_chat_messages WHERE session_id = ? AND id < ? LIMIT 1'
        );
        $moreSt->execute([$sessionId, $oldestId]);
        $hasMore = (bool) $moreSt->fetchColumn();
    }

    gos_api_json([
        'ok' => true,
        'messages' => $messages,
        'has_more' => $hasMore,
        'total' => $total,
        'oldest_id' => $oldestId,
        'newest_id' => $newestId,
        'limit' => $usePaging ? $limit : null,
        'before_id' => $beforeId > 0 ? $beforeId : null,
    ]);
}

if ($method === 'POST') {
    $body = gos_json_body();
    $action = (string) ($body['action'] ?? 'create');

    if ($action === 'toggle_exclude') {
        $msgId = (int) ($body['message_id'] ?? 0);
        $excluded = !empty($body['excluded']) ? 1 : 0;
        if ($msgId < 1) {
            gos_api_json(['ok' => false, 'error' => 'validation'], 400);
        }
        $st = gos_pdo()->prepare(
            'UPDATE system_chat_messages m
             INNER JOIN system_chat_sessions s ON s.id = m.session_id
             SET m.excluded_from_context = ?
             WHERE m.id = ? AND s.user_id = ?'
        );
        $st->execute([$excluded, $msgId, $userId]);
        gos_system_chat_audit('info', 'message', 'Message context exclusion toggled', [
            'message_id' => $msgId,
            'excluded' => $excluded,
        ], $userId);
        gos_api_json(['ok' => true]);
    }

    if ($action === 'delete') {
        $msgId = (int) ($body['message_id'] ?? 0);
        if ($msgId < 1) {
            gos_api_json(['ok' => false, 'error' => 'validation'], 400);
        }
        $st = gos_pdo()->prepare(
            'DELETE m FROM system_chat_messages m
             INNER JOIN system_chat_sessions s ON s.id = m.session_id
             WHERE m.id = ? AND s.user_id = ?'
        );
        $st->execute([$msgId, $userId]);
        gos_system_chat_audit('info', 'message', 'Message deleted', ['message_id' => $msgId], $userId);
        gos_api_json(['ok' => true]);
    }

    if ($action === 'edit') {
        $msgId = (int) ($body['message_id'] ?? 0);
        $content = (string) ($body['content'] ?? '');
        if ($msgId < 1 || $content === '' || mb_strlen($content) > 100000) {
            gos_api_json(['ok' => false, 'error' => 'validation'], 400);
        }
        $st = gos_pdo()->prepare(
            'UPDATE system_chat_messages m
             INNER JOIN system_chat_sessions s ON s.id = m.session_id
             SET m.content = ?
             WHERE m.id = ? AND s.user_id = ? AND m.role = ?'
        );
        $st->execute([$content, $msgId, $userId, 'user']);
        if ($st->rowCount() < 1) {
            gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
        }
        gos_system_chat_audit('info', 'message', 'Message edited', [
            'message_id' => $msgId,
            'content_len' => mb_strlen($content),
        ], $userId);
        gos_api_json(['ok' => true]);
    }

    if ($action === 'stream_upsert') {
        $sessionId = (string) ($body['session_id'] ?? '');
        $msgId = (int) ($body['message_id'] ?? 0);
        $content = (string) ($body['content'] ?? '');
        $finalize = !empty($body['finalize']);
        $meta = $body['metadata'] ?? null;

        if (!gos_system_chat_session_owned($sessionId, $userId)) {
            gos_api_json(['ok' => false, 'error' => 'invalid_session'], 400);
        }
        if (mb_strlen($content) > 100000) {
            gos_api_json(['ok' => false, 'error' => 'invalid_content'], 400);
        }

        if (is_array($meta)) {
            if ($finalize) {
                unset($meta['streaming']);
            } else {
                $meta['streaming'] = true;
            }
        } elseif ($finalize) {
            $meta = null;
        } else {
            $meta = ['streaming' => true];
        }

        $metaJson = $meta !== null ? json_encode($meta, JSON_UNESCAPED_UNICODE) : null;
        $inTok = (int) ($body['input_tokens'] ?? 0);
        $outTok = (int) ($body['output_tokens'] ?? 0);

        if ($msgId > 0) {
            $st = gos_pdo()->prepare(
                'UPDATE system_chat_messages m
                 INNER JOIN system_chat_sessions s ON s.id = m.session_id
                 SET m.content = ?, m.metadata = ?, m.input_tokens = ?, m.output_tokens = ?
                 WHERE m.id = ? AND s.user_id = ? AND m.session_id = ? AND m.role = ?'
            );
            $st->execute([$content, $metaJson, $inTok, $outTok, $msgId, $userId, $sessionId, 'assistant']);
            if ($st->rowCount() < 1) {
                gos_api_json(['ok' => false, 'error' => 'not_found'], 404);
            }
        } else {
            $st = gos_pdo()->prepare(
                'INSERT INTO system_chat_messages (session_id, role, content, metadata, input_tokens, output_tokens)
                 VALUES (?, ?, ?, ?, ?, ?)'
            );
            $st->execute([$sessionId, 'assistant', $content, $metaJson, $inTok, $outTok]);
            $msgId = (int) gos_pdo()->lastInsertId();
        }

        gos_pdo()->prepare('UPDATE system_chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?')
            ->execute([$sessionId]);

        gos_api_json(['ok' => true, 'id' => $msgId, 'finalized' => $finalize]);
    }

    $sessionId = (string) ($body['session_id'] ?? '');
    $role = (string) ($body['role'] ?? '');
    $content = (string) ($body['content'] ?? '');

    if (!gos_system_chat_session_owned($sessionId, $userId)) {
        gos_api_json(['ok' => false, 'error' => 'invalid_session'], 400);
    }
    if (!in_array($role, ['user', 'assistant', 'system'], true)) {
        gos_api_json(['ok' => false, 'error' => 'invalid_role'], 400);
    }
    if ($content === '' || mb_strlen($content) > 100000) {
        gos_api_json(['ok' => false, 'error' => 'invalid_content'], 400);
    }

    $meta = isset($body['metadata']) ? json_encode($body['metadata'], JSON_UNESCAPED_UNICODE) : null;
    $inTok = (int) ($body['input_tokens'] ?? 0);
    $outTok = (int) ($body['output_tokens'] ?? 0);

    $st = gos_pdo()->prepare(
        'INSERT INTO system_chat_messages (session_id, role, content, metadata, input_tokens, output_tokens)
         VALUES (?, ?, ?, ?, ?, ?)'
    );
    $st->execute([$sessionId, $role, $content, $meta, $inTok, $outTok]);
    $msgId = (int) gos_pdo()->lastInsertId();

    gos_pdo()->prepare('UPDATE system_chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?')
        ->execute([$sessionId]);

    $sessionTitle = null;
    if ($role === 'user') {
        $titleSt = gos_pdo()->prepare('SELECT title FROM system_chat_sessions WHERE id = ? LIMIT 1');
        $titleSt->execute([$sessionId]);
        $currentTitle = (string) ($titleSt->fetchColumn() ?: '');
        $userCountSt = gos_pdo()->prepare(
            'SELECT COUNT(*) FROM system_chat_messages WHERE session_id = ? AND role = ?'
        );
        $userCountSt->execute([$sessionId, 'user']);
        $userCount = (int) $userCountSt->fetchColumn();
        $defaults = ['New Chat', 'New chat', 'Grokify', 'GrokifyOS', 'Chat', ''];
        if ($userCount === 1 && in_array($currentTitle, $defaults, true)) {
            $auto = gos_system_chat_auto_title($content);
            if ($auto !== '') {
                gos_pdo()->prepare(
                    'UPDATE system_chat_sessions SET title = ? WHERE id = ?'
                )->execute([$auto, $sessionId]);
                $sessionTitle = $auto;
            }
        }
        if ($sessionTitle === null) {
            $sessionTitle = $currentTitle !== '' ? $currentTitle : null;
        }
    }

    gos_system_chat_audit('info', 'message', 'Message saved', [
        'session_id' => $sessionId,
        'role' => $role,
        'content_len' => mb_strlen($content),
        'message_id' => $msgId,
    ], $userId, $sessionId);

    $payload = ['ok' => true, 'id' => $msgId];
    if ($sessionTitle !== null && $sessionTitle !== '') {
        $payload['session_title'] = $sessionTitle;
    }
    gos_api_json($payload);
}

gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
