#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
PORT="$(sed -n 's/^ASKPHOTOS_HTTP_PORT=//p' .env | tail -1)"
PORT="${PORT:-8080}"
BASE_URL="${ASKPHOTOS_BASE_URL:-http://127.0.0.1:$PORT}"

curl --fail --silent "$BASE_URL/api/health" | grep -q '"ok"'
python3 scripts/verify_demo_library.py

PUBLISHED="$(docker compose ps --format json | python3 -c \
  "import json,sys; data=json.load(sys.stdin); rows=data if isinstance(data,list) else [data]; print(' '.join(r.get('Service','') for r in rows if r.get('Publishers')))")"
if [[ "$PUBLISHED" != "gateway" ]]; then
  echo "Unexpected published services: ${PUBLISHED:-none}" >&2
  exit 1
fi

docker compose exec -T api alembic -c alembic.ini current | grep -q "head"
echo "Health, demo licences, private-port policy and database migration checks passed."
