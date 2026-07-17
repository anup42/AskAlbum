from __future__ import annotations

from pathlib import Path

from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import sessionmaker

from app.config import settings
from app.database import Base
from app.model_client import LocalModelClient
from app.models import Photo, PhotoArtifact, PhotoEmbedding
from app.pipeline import run_photo_pipeline


def test_pipeline_is_versioned_idempotent_and_partial_on_adapter_failure(tmp_path, monkeypatch) -> None:
    engine = create_engine(f"sqlite:///{(tmp_path / 'pipeline.db').as_posix()}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine, expire_on_commit=False)
    image_path = Path(settings.demo_dir) / "images" / "lake-seven.jpg"

    monkeypatch.setattr(settings, "local_models_enabled", True)
    monkeypatch.setattr(LocalModelClient, "embed_image", lambda self, path: [0.0] * 1151 + [1.0])
    monkeypatch.setattr(
        LocalModelClient,
        "ocr",
        lambda self, path: {"text": "Lake", "blocks": [{"text": "Lake", "confidence": 0.99}]},
    )
    monkeypatch.setattr(
        LocalModelClient,
        "qwen_image",
        lambda self, path, prompt, max_tokens=220: '{"caption":"A lake beside mountains.","tags":["lake","mountains"]}',
    )

    with session_factory() as db:
        db.add(
            Photo(
                id="pipeline-photo",
                owner_id=None,
                scope="demo",
                title="Pipeline photo",
                filename=image_path.name,
                sha256="a" * 64,
                original_path=str(image_path),
                thumbnail_path=str(image_path),
                width=640,
                height=480,
                tags=[],
                alt_text="",
                status="ready",
            )
        )
        db.commit()
        assert run_photo_pipeline(db, "pipeline-photo")["status"] == "ready"
        assert run_photo_pipeline(db, "pipeline-photo")["status"] == "ready"
        assert db.scalar(select(func.count(PhotoArtifact.id))) == 3
        assert db.scalar(select(func.count(PhotoEmbedding.id))) == 1

        monkeypatch.setattr(LocalModelClient, "ocr", lambda self, path: (_ for _ in ()).throw(RuntimeError()))
        assert run_photo_pipeline(db, "pipeline-photo", force=True)["status"] == "partial"
        assert db.get(Photo, "pipeline-photo").status == "partial"
        ocr = db.scalar(
            select(PhotoArtifact).where(
                PhotoArtifact.photo_id == "pipeline-photo",
                PhotoArtifact.artifact_kind == "ocr",
            )
        )
        assert ocr.status == "failed"
        assert ocr.error_code == "ocr_unavailable"

    engine.dispose()
