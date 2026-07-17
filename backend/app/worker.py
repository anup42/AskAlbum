from celery import Celery

from .config import settings
from .database import SessionLocal
from .models import Photo
from .pipeline import rebuild_events, run_photo_pipeline


celery_app = Celery("askphotos", broker=settings.redis_url, backend=settings.redis_url)
celery_app.conf.update(
    task_always_eager=settings.celery_always_eager,
    task_eager_propagates=True,
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
)


@celery_app.task(
    bind=True,
    name="askphotos.index_photo",
    autoretry_for=(ConnectionError,),
    retry_backoff=True,
    retry_kwargs={"max_retries": 3},
)
def index_photo(self, photo_id: str, force: bool = False) -> dict[str, str]:
    with SessionLocal() as db:
        result = run_photo_pipeline(db, photo_id, force=force)
        photo = db.get(Photo, photo_id)
        if photo and photo.owner_id:
            rebuild_events(db, photo.owner_id)
        return result
