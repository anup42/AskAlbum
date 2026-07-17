#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="${1:-}"
[[ -f "$SOURCE" ]] || {
  echo "Usage: bash scripts/gpu/import-places.sh places.csv" >&2
  echo "CSV columns: name,country_code,latitude,longitude,population" >&2
  exit 2
}
cd "$ROOT"
docker compose exec -T api python -m app.place_import - < "$SOURCE"
