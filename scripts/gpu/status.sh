#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
docker compose ps
echo
echo "GPU usage:"
nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv,noheader 2>/dev/null || true
echo
echo "Model health:"
docker compose exec -T model-service python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8091/health').read().decode())"
docker compose exec -T qwen python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/health').read().decode())"
