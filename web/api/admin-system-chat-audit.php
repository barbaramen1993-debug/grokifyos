<?php

declare(strict_types=1);

require_once __DIR__ . '/_common.php';

$access = gos_require_system_chat();
$userId = (int) $access['user']['id'];

$action = (string) ($_GET['action'] ?? '');
if ($action === '' && ($_SERVER['REQUEST_METHOD'] ?? '') === 'POST') {
    $body = gos_json_body();
    $action = (string) ($body['action'] ?? 'list');
}

if ($action === 'stream') {
    @ini_set('zlib.output_compression', '0');
    @ini_set('output_buffering', '0');
    while (ob_get_level()) {
        ob_end_clean();
    }
    header('Content-Type: text/event-stream');
    header('Cache-Control: no-cache');
    header('Connection: keep-alive');
    header('X-Accel-Buffering: no');

    gos_system_chat_audit('info', 'access', 'Audit stream opened', [], $userId);

    $sinceId = (int) ($_GET['since_id'] ?? 0);
    $category = trim((string) ($_GET['category'] ?? ''));
    $level = trim((string) ($_GET['level'] ?? ''));
    $sessionId = trim((string) ($_GET['session_id'] ?? ''));
    $maxRun = 280;
    $start = time();

    echo 'event: connected' . "\n";
    echo 'data: ' . json_encode(['ok' => true]) . "\n\n";
    flush();

    while ((time() - $start) < $maxRun) {
        $result = gos_system_chat_audit_list([
            'since_id' => $sinceId,
            'category' => $category,
            'level' => $level,
            'session_id' => $sessionId,
            'limit' => 50,
            'offset' => 0,
        ]);
        $events = $result['events'];
        if ($events !== []) {
            $chronological = array_reverse($events);
            foreach ($chronological as $ev) {
                $sinceId = max($sinceId, (int) $ev['id']);
                echo "event: audit\n";
                echo 'data: ' . json_encode($ev, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n\n";
            }
            flush();
        } else {
            echo ": heartbeat\n\n";
            flush();
        }
        usleep(800000);
    }

    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    gos_api_json(['ok' => false, 'error' => 'method_not_allowed'], 405);
}
$body = gos_json_body();

$result = gos_system_chat_audit_list([
    'limit' => (int) ($body['limit'] ?? 100),
    'offset' => (int) ($body['offset'] ?? 0),
    'level' => $body['level'] ?? '',
    'category' => $body['category'] ?? '',
    'session_id' => $body['session_id'] ?? '',
    'since_id' => (int) ($body['since_id'] ?? 0),
]);

gos_api_json([
    'ok' => true,
    'events' => $result['events'],
    'total' => $result['total'],
    'categories' => ['access', 'connection', 'message', 'agent', 'process', 'agent_done', 'error'],
]);
