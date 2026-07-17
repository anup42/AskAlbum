#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$ROOT/.env"
MODELS_DIR="./models"
HTTP_PORT="8080"
ADMIN_PASSWORD="${ASKPHOTOS_ADMIN_PASSWORD:-}"

usage() {
  echo "Usage: bash scripts/gpu/configure.sh [--admin-password VALUE] [--http-port PORT] [--models-dir PATH] [--force]"
}

FORCE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --admin-password) ADMIN_PASSWORD="${2:-}"; shift 2 ;;
    --http-port) HTTP_PORT="${2:-}"; shift 2 ;;
    --models-dir) MODELS_DIR="${2:-}"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

if [[ -f "$ENV_FILE" && "$FORCE" != "1" ]]; then
  echo "$ENV_FILE already exists; leaving it unchanged."
  exit 0
fi

if [[ -z "$ADMIN_PASSWORD" && -t 0 ]]; then
  read -r -s -p "Choose the AskPhotos admin password: " ADMIN_PASSWORD
  echo
fi
if [[ ${#ADMIN_PASSWORD} -lt 12 ]]; then
  echo "Provide an admin password of at least 12 characters with --admin-password." >&2
  exit 2
fi
if [[ ! "$ADMIN_PASSWORD" =~ ^[A-Za-z0-9@%+=:,._-]+$ ]]; then
  echo "Use letters, numbers, or these symbols in the admin password: @ % + = : , . _ -" >&2
  exit 2
fi
if [[ ! "$HTTP_PORT" =~ ^[0-9]+$ ]] || (( HTTP_PORT < 1 || HTTP_PORT > 65535 )); then
  echo "HTTP port must be between 1 and 65535." >&2
  exit 2
fi
if [[ "$MODELS_DIR" =~ [[:space:]] ]]; then
  echo "The models directory must not contain whitespace." >&2
  exit 2
fi

SECRET_KEY="$(openssl rand -hex 32)"
POSTGRES_PASSWORD="$(openssl rand -hex 24)"
umask 077
cat > "$ENV_FILE" <<EOF
ASKPHOTOS_ENV=production
ASKPHOTOS_SECRET_KEY=$SECRET_KEY
ASKPHOTOS_ADMIN_USERNAME=admin
ASKPHOTOS_ADMIN_PASSWORD=$ADMIN_PASSWORD
ASKPHOTOS_COOKIE_SECURE=false
ASKPHOTOS_POSTGRES_PASSWORD=$POSTGRES_PASSWORD
ASKPHOTOS_DATABASE_URL=postgresql+psycopg://askphotos:$POSTGRES_PASSWORD@db:5432/askphotos
ASKPHOTOS_REDIS_URL=redis://redis:6379/0
ASKPHOTOS_CELERY_ALWAYS_EAGER=false
ASKPHOTOS_AUTO_INDEX_ON_START=true
ASKPHOTOS_LOCAL_MODELS_ENABLED=true
ASKPHOTOS_DATA_DIR=/data
ASKPHOTOS_DEMO_DIR=/demo-assets
ASKPHOTOS_DEVELOPER_FEATURE_ENABLED=true
ASKPHOTOS_QWEN_MODEL_DIR=/models/qwen
ASKPHOTOS_SIGLIP_MODEL_DIR=/models/siglip
ASKPHOTOS_PADDLEOCR_MODEL_DIR=/models/paddleocr
ASKPHOTOS_SFACE_MODEL_PATH=/models/sface/sface.onnx
ASKPHOTOS_WHISPER_MODEL_DIR=/models/whisper
ASKPHOTOS_YUNET_MODEL_PATH=/models/sface/yunet.onnx
ASKPHOTOS_MODEL_DEVICE=cuda
ASKPHOTOS_MODEL_SERVICE_URL=http://model-service:8091
ASKPHOTOS_QWEN_BASE_URL=http://qwen:8000/v1
ASKPHOTOS_QWEN_MODEL_NAME=Qwen3-VL-8B-Instruct-FP8
ASKPHOTOS_PIPELINE_VERSION=local-multimodal-v1
ASKPHOTOS_MODEL_REQUEST_TIMEOUT_SECONDS=90
ASKPHOTOS_VISUAL_VERIFICATION_LIMIT=6
ASKPHOTOS_RATE_LIMIT_ENABLED=true
ASKPHOTOS_RATE_LIMIT_PER_MINUTE=120
ASKPHOTOS_TRUSTED_PROXY_COUNT=1
ASKPHOTOS_QWEN_GPU_MEMORY_UTILIZATION=0.62
ASKPHOTOS_QWEN_MAX_MODEL_LEN=8192
ASKPHOTOS_MODELS_DIR=$MODELS_DIR
ASKPHOTOS_HTTP_PORT=$HTTP_PORT
EOF
chmod 600 "$ENV_FILE"
echo "Created $ENV_FILE. It contains secrets; do not commit or share it."
