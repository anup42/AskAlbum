#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

docker compose exec -T api python -c \
  "from sqlalchemy import select; from app.database import SessionLocal; from app.models import Photo; from app.worker import index_photo; db=SessionLocal(); ids=list(db.scalars(select(Photo.id)).all()); db.close(); [index_photo.delay(photo_id, True) for photo_id in ids]; print(f'Queued {len(ids)} photos for re-indexing.')"
