#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP="${1:-}"
[[ -n "$BACKUP" ]] || { echo "Usage: bash scripts/gpu/restore.sh /absolute/path/to/backup" >&2; exit 2; }
BACKUP="$(cd "$BACKUP" && pwd)"
[[ -f "$BACKUP/database.sql" && -f "$BACKUP/media.tar.gz" && -f "$BACKUP/SHA256SUMS" ]] || {
  echo "The backup is incomplete." >&2
  exit 1
}

cd "$BACKUP"
sha256sum --check SHA256SUMS
cd "$ROOT"
if [[ "${ASKPHOTOS_RESTORE_CONFIRM:-}" != "RESTORE" ]]; then
  echo "Restore replaces the current database and media volume."
  echo "Run again with ASKPHOTOS_RESTORE_CONFIRM=RESTORE after taking a current backup."
  exit 2
fi

docker compose stop gateway api worker model-service qwen
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U askphotos -d askphotos \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U askphotos -d askphotos < "$BACKUP/database.sql"
docker compose run --rm --no-deps \
  -e ASKPHOTOS_SKIP_MIGRATIONS=1 \
  -v "$BACKUP:/backup:ro" \
  api sh -c "find /data -mindepth 1 -delete && tar -C /data -xzf /backup/media.tar.gz"
docker compose up -d
echo "Restore completed. Run: bash scripts/gpu/status.sh"
