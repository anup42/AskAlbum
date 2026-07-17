#!/usr/bin/env sh
set -eu

cd /app/backend
if [ "${ASKPHOTOS_SKIP_MIGRATIONS:-0}" != "1" ]; then
  alembic -c alembic.ini upgrade head
fi
exec "$@"
