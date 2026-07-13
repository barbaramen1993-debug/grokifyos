#!/usr/bin/env bash
# Sync Grok CLI auth.json into a path PHP-FPM (www-data) can read for usage/API.
# Run after `grok login` or whenever usage shows auth_missing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${GROK_AUTH_SRC:-/root/.grok/auth.json}"
# Always land in the web-readable storage path. Shell env may point GROKIFY_GROK_AUTH_JSON
# at ~/.grok/auth.json (root-only), which would make install a no-op same-file copy.
DEST_DEFAULT="$ROOT/storage/grok-auth.json"
DEST="$DEST_DEFAULT"
if [[ -n "${GROKIFY_GROK_AUTH_JSON:-}" && "${GROKIFY_GROK_AUTH_JSON}" != "$SRC" ]]; then
  DEST="${GROKIFY_GROK_AUTH_JSON}"
fi
# Never "sync" a file onto itself.
if [[ "$(readlink -f "$SRC" 2>/dev/null || echo "$SRC")" == "$(readlink -f "$DEST" 2>/dev/null || echo "$DEST")" ]]; then
  DEST="$DEST_DEFAULT"
fi

if [[ ! -r "$SRC" ]]; then
  echo "error: cannot read source auth: $SRC" >&2
  echo "  run: grok login --device-code" >&2
  exit 1
fi

install -o www-data -g www-data -m 640 "$SRC" "$DEST"
echo "synced $SRC → $DEST (www-data:640)"

# Keep .env pointed at the web-readable copy when present
ENV_FILE="$ROOT/.env"
if [[ -f "$ENV_FILE" ]]; then
  if grep -q '^GROKIFY_GROK_AUTH_JSON=' "$ENV_FILE"; then
    sed -i "s|^GROKIFY_GROK_AUTH_JSON=.*|GROKIFY_GROK_AUTH_JSON=$DEST|" "$ENV_FILE"
  else
    echo "GROKIFY_GROK_AUTH_JSON=$DEST" >>"$ENV_FILE"
  fi
  echo "updated .env GROKIFY_GROK_AUTH_JSON=$DEST"
fi

# Quick probe as www-data when php is available
if command -v php >/dev/null && id www-data >/dev/null 2>&1; then
  sudo -u www-data php -r '
    require_once "'"$ROOT"'/web/includes/bootstrap.php";
    $u = gos_grok_build_fetch_usage(true);
    if (!empty($u["ok"])) {
      echo "probe OK: usage_percent=" . ($u["usage_percent"] ?? "?") . "\n";
      exit(0);
    }
    fwrite(STDERR, "probe failed: " . json_encode($u) . "\n");
    exit(2);
  ' || true
fi
