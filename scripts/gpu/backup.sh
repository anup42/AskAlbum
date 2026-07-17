#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DESTINATION="${1:-$ROOT/backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$(mkdir -p "$DESTINATION" && cd "$DESTINATION" && pwd)/$STAMP"
mkdir -p "$BACKUP_DIR"
cd "$ROOT"

resume_services() {
  docker compose up -d api worker gateway >/dev/null
}
trap resume_services EXIT
docker compose stop gateway api worker

docker compose exec -T db pg_dump --clean --if-exists --no-owner -U askphotos -d askphotos > "$BACKUP_DIR/database.sql"
docker compose run --rm --no-deps \
  -e ASKPHOTOS_SKIP_MIGRATIONS=1 \
  -v "$BACKUP_DIR:/backup" \
  api tar -C /data -czf /backup/media.tar.gz .
MODELS_DIR="$(sed -n 's/^ASKPHOTOS_MODELS_DIR=//p' .env | tail -1)"
MODELS_DIR="${MODELS_DIR:-./models}"
cp "$MODELS_DIR/manifest.json" "$BACKUP_DIR/model-manifest.json"
printf '{"created_at":"%s","pipeline_version":"%s"}\n' \
  "$STAMP" \
  "$(sed -n 's/^ASKPHOTOS_PIPELINE_VERSION=//p' .env | tail -1)" \
  > "$BACKUP_DIR/backup.json"
(cd "$BACKUP_DIR" && sha256sum database.sql media.tar.gz model-manifest.json > SHA256SUMS)
trap - EXIT
resume_services
echo "Backup created at $BACKUP_DIR"
