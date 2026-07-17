#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

command -v docker >/dev/null || { echo "Docker Engine is not installed." >&2; exit 1; }
docker compose version >/dev/null || { echo "Docker Compose v2 is not available." >&2; exit 1; }
command -v nvidia-smi >/dev/null || { echo "The NVIDIA driver or nvidia-smi is unavailable." >&2; exit 1; }
docker info >/dev/null || { echo "Docker is not available to this user." >&2; exit 1; }

echo "GPU:"
nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
echo "Testing NVIDIA Container Toolkit:"
docker run --rm --gpus all nvidia/cuda:12.8.1-base-ubuntu24.04 nvidia-smi --query-gpu=name --format=csv,noheader
echo "Host checks passed."
