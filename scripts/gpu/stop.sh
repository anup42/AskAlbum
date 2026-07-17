#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
docker compose down
echo "AskPhotos stopped. Database, Redis and photo volumes were preserved."
