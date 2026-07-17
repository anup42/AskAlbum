#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

[[ -f .env ]] || { echo "Run: bash scripts/gpu/configure.sh" >&2; exit 1; }
MODELS_DIR="$(sed -n 's/^ASKPHOTOS_MODELS_DIR=//p' .env | tail -1)"
MODELS_DIR="${MODELS_DIR:-./models}"
[[ -f "$MODELS_DIR/manifest.json" ]] || {
  echo "Run: bash scripts/gpu/download-models.sh --models-dir '$MODELS_DIR'" >&2
  exit 1
}

docker compose config --quiet
docker compose up -d --build

PORT="$(sed -n 's/^ASKPHOTOS_HTTP_PORT=//p' .env | tail -1)"
PORT="${PORT:-8080}"
echo "Waiting for the photo library at http://127.0.0.1:$PORT ..."
for _ in $(seq 1 90); do
  if curl --fail --silent "http://127.0.0.1:$PORT/api/health" >/dev/null &&
     curl --fail --silent "http://127.0.0.1:$PORT/" >/dev/null; then
    echo "AskPhotos is ready at http://$(hostname -I | awk '{print $1}'):$PORT"
    echo "Local models continue warming in the background; check with: bash scripts/gpu/status.sh"
    exit 0
  fi
  sleep 2
done

echo "The web service did not become ready. Recent API logs:" >&2
docker compose logs --tail=80 api gateway >&2
exit 1
