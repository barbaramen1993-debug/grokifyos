<?php

declare(strict_types=1);

/**
 * Browser APK upload removed — publish releases from the host:
 *   android/scripts/publish.sh
 *   php android/scripts/publish-apk.php …
 */
require_once __DIR__ . '/_common.php';

gos_api_json([
    'ok' => false,
    'error' => 'upload_removed',
    'message' => 'Use android/scripts/publish.sh on the host to register APK releases.',
], 410);
