#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VENV="$ROOT/.model-download-venv"

command -v python3 >/dev/null || { echo "Python 3 is required." >&2; exit 1; }
if [[ ! -x "$VENV/bin/python" ]]; then
  python3 -m venv "$VENV"
  "$VENV/bin/python" -m pip install --upgrade pip
  "$VENV/bin/python" -m pip install "huggingface-hub==0.35.3" "requests==2.32.5"
fi

cd "$ROOT"
exec "$VENV/bin/python" scripts/gpu/download_models.py "$@"
