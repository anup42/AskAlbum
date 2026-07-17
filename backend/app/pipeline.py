from __future__ import annotations

import math
from datetime import timedelta
from pathlib import Path
from uuid import uuid4

from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .config import settings
from .model_client import LocalModelClient
from .models import (
    PersonCluster,
    Photo,
    PhotoArtifact,
    PhotoEmbedding,
    PhotoEvent,
    PhotoFace,
    User,
)
from .prompts.caption import CaptionInput, build_caption_prompt, parse_caption_output


ARTIFACT_EMBEDDING = "image_embedding"
ARTIFACT_OCR = "ocr"
ARTIFACT_CAPTION = "caption"
ARTIFACT_FACES = "faces"
ARTIFACT_TRANSCRIPT = "transcript"
FACE_CLUSTER_COSINE_THRESHOLD = 0.48


def artifact_for(db: Session, photo_id: str, kind: str) -> PhotoArtifact:
    artifact = db.scalar(
        select(PhotoArtifact).where(
            PhotoArtifact.photo_id == photo_id,
            PhotoArtifact.artifact_kind == kind,
            PhotoArtifact.pipeline_version == settings.pipeline_version,
        )
    )
    if artifact is None:
        artifact = PhotoArtifact(
            photo_id=photo_id,
            artifact_kind=kind,
            pipeline_version=settings.pipeline_version,
            status="pending",
        )
        db.add(artifact)
        try:
            db.commit()
            db.refresh(artifact)
        except IntegrityError:
            db.rollback()
            artifact = db.scalar(
                select(PhotoArtifact).where(
                    PhotoArtifact.photo_id == photo_id,
                    PhotoArtifact.artifact_kind == kind,
                    PhotoArtifact.pipeline_version == settings.pipeline_version,
                )
            )
            if artifact is None:
                raise
    return artifact


def begin_artifact(db: Session, photo_id: str, kind: str, force: bool = False) -> PhotoArtifact | None:
    artifact = artifact_for(db, photo_id, kind)
    if artifact.status == "ready" and not force:
        return None
    artifact.status = "processing"
    artifact.error_code = None
    artifact.attempts += 1
    db.add(artifact)
    db.commit()
    return artifact


def finish_artifact(db: Session, artifact: PhotoArtifact, payload: dict) -> None:
    artifact.status = "ready"
    artifact.payload = payload
    artifact.error_code = None
    db.add(artifact)
    db.commit()


def fail_artifact(db: Session, artifact: PhotoArtifact, code: str) -> None:
    artifact.status = "failed"
    artifact.payload = None
    artifact.error_code = code[:80]
    db.add(artifact)
    db.commit()


def upsert_embedding(db: Session, photo_id: str, embedding: list[float]) -> None:
    row = db.scalar(
        select(PhotoEmbedding).where(
            PhotoEmbedding.photo_id == photo_id,
            PhotoEmbedding.pipeline_version == settings.pipeline_version,
        )
    )
    if row is None:
        row = PhotoEmbedding(
            photo_id=photo_id,
            pipeline_version=settings.pipeline_version,
            embedding=embedding,
        )
    else:
        row.embedding = embedding
    db.add(row)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        current = db.scalar(
            select(PhotoEmbedding).where(
                PhotoEmbedding.photo_id == photo_id,
                PhotoEmbedding.pipeline_version == settings.pipeline_version,
            )
        )
        if current is None:
            raise
        current.embedding = embedding
        db.add(current)
        db.commit()


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if not left or len(left) != len(right):
        return -1.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    return dot / (left_norm * right_norm) if left_norm and right_norm else -1.0


def assign_person_cluster(db: Session, owner_id: int, embedding: list[float]) -> PersonCluster:
    clusters = list(
        db.scalars(
            select(PersonCluster).where(PersonCluster.owner_id == owner_id, PersonCluster.hidden.is_(False))
        ).all()
    )
    best = max(clusters, key=lambda item: cosine_similarity(item.centroid, embedding), default=None)
    if best is not None and cosine_similarity(best.centroid, embedding) >= FACE_CLUSTER_COSINE_THRESHOLD:
        count = max(best.sample_count, 1)
        centroid = [(old * count + new) / (count + 1) for old, new in zip(best.centroid, embedding)]
        norm = math.sqrt(sum(value * value for value in centroid)) or 1.0
        best.centroid = [value / norm for value in centroid]
        best.sample_count = count + 1
        db.add(best)
        db.commit()
        return best
    cluster = PersonCluster(
        id=uuid4().hex,
        owner_id=owner_id,
        name=None,
        centroid=embedding,
        sample_count=1,
    )
    db.add(cluster)
    db.commit()
    db.refresh(cluster)
    return cluster


def store_faces(db: Session, photo: Photo, faces: list[dict]) -> None:
    if photo.owner_id is None:
        return
    previous_cluster_ids = set(
        db.scalars(
            select(PhotoFace.cluster_id).where(
                PhotoFace.photo_id == photo.id,
                PhotoFace.pipeline_version == settings.pipeline_version,
                PhotoFace.cluster_id.is_not(None),
            )
        ).all()
    )
    db.execute(
        delete(PhotoFace).where(
            PhotoFace.photo_id == photo.id,
            PhotoFace.pipeline_version == settings.pipeline_version,
        )
    )
    db.commit()
    for cluster_id in previous_cluster_ids:
        cluster = db.get(PersonCluster, cluster_id)
        if cluster is None:
            continue
        remaining = list(
            db.scalars(
                select(PhotoFace).where(
                    PhotoFace.cluster_id == cluster_id,
                    PhotoFace.pipeline_version == settings.pipeline_version,
                )
            ).all()
        )
        if not remaining:
            db.delete(cluster)
            continue
        dimensions = len(remaining[0].embedding)
        centroid = [
            sum(face.embedding[index] for face in remaining) / len(remaining)
            for index in range(dimensions)
        ]
        norm = math.sqrt(sum(value * value for value in centroid)) or 1.0
        cluster.centroid = [value / norm for value in centroid]
        cluster.sample_count = len(remaining)
        db.add(cluster)
    db.commit()
    for index, face in enumerate(faces):
        embedding = [float(value) for value in face.get("embedding", [])]
        if not embedding:
            continue
        cluster = assign_person_cluster(db, photo.owner_id, embedding)
        db.add(
            PhotoFace(
                photo_id=photo.id,
                owner_id=photo.owner_id,
                cluster_id=cluster.id,
                face_index=index,
                box=[float(value) for value in face.get("box", [])[:4]],
                embedding=embedding,
                pipeline_version=settings.pipeline_version,
            )
        )
    db.commit()


def run_photo_pipeline(db: Session, photo_id: str, force: bool = False) -> dict[str, str]:
    photo = db.get(Photo, photo_id)
    if photo is None:
        return {"photo_id": photo_id, "status": "missing", "pipeline_version": settings.pipeline_version}
    if not settings.local_models_enabled:
        photo.pipeline_version = settings.pipeline_version
        photo.status = "ready"
        db.add(photo)
        db.commit()
        return {
            "photo_id": photo.id,
            "status": "disabled",
            "pipeline_version": settings.pipeline_version,
        }
    photo.status = "processing"
    db.add(photo)
    db.commit()
    client = LocalModelClient()
    source = Path(photo.thumbnail_path if Path(photo.thumbnail_path).exists() else photo.original_path)
    results: dict[str, str] = {}

    embedding_artifact = begin_artifact(db, photo.id, ARTIFACT_EMBEDDING, force)
    if embedding_artifact:
        try:
            embedding = client.embed_image(source)
            upsert_embedding(db, photo.id, embedding)
            finish_artifact(db, embedding_artifact, {"dimensions": len(embedding)})
            results[ARTIFACT_EMBEDDING] = "ready"
        except Exception:
            fail_artifact(db, embedding_artifact, "embedding_unavailable")
            results[ARTIFACT_EMBEDDING] = "failed"

    ocr_artifact = begin_artifact(db, photo.id, ARTIFACT_OCR, force)
    if ocr_artifact:
        try:
            payload = client.ocr(source)
            finish_artifact(db, ocr_artifact, payload)
            results[ARTIFACT_OCR] = "ready"
        except Exception:
            fail_artifact(db, ocr_artifact, "ocr_unavailable")
            results[ARTIFACT_OCR] = "failed"

    caption_artifact = begin_artifact(db, photo.id, ARTIFACT_CAPTION, force)
    if caption_artifact:
        try:
            caption = parse_caption_output(
                client.qwen_image(source, build_caption_prompt(CaptionInput()), max_tokens=220)
            )
            payload = caption.model_dump()
            finish_artifact(db, caption_artifact, payload)
            if payload["caption"]:
                photo.alt_text = payload["caption"]
            if payload["tags"]:
                photo.tags = list(dict.fromkeys([*(photo.tags or []), *payload["tags"]]))[:30]
            db.add(photo)
            db.commit()
            results[ARTIFACT_CAPTION] = "ready"
        except Exception:
            fail_artifact(db, caption_artifact, "caption_unavailable")
            results[ARTIFACT_CAPTION] = "failed"

    owner = db.get(User, photo.owner_id) if photo.owner_id else None
    if owner and owner.face_indexing_enabled:
        face_artifact = begin_artifact(db, photo.id, ARTIFACT_FACES, force)
        if face_artifact:
            try:
                faces = client.faces(source)
                store_faces(db, photo, faces)
                finish_artifact(db, face_artifact, {"count": len(faces)})
                results[ARTIFACT_FACES] = "ready"
            except Exception:
                fail_artifact(db, face_artifact, "faces_unavailable")
                results[ARTIFACT_FACES] = "failed"

    if photo.content_type.startswith(("audio/", "video/")):
        transcript_artifact = begin_artifact(db, photo.id, ARTIFACT_TRANSCRIPT, force)
        if transcript_artifact:
            try:
                finish_artifact(db, transcript_artifact, client.transcribe(Path(photo.original_path)))
                results[ARTIFACT_TRANSCRIPT] = "ready"
            except Exception:
                fail_artifact(db, transcript_artifact, "transcription_unavailable")
                results[ARTIFACT_TRANSCRIPT] = "failed"

    photo.pipeline_version = settings.pipeline_version
    photo.status = "partial" if "failed" in results.values() else "ready"
    db.add(photo)
    db.commit()
    return {
        "photo_id": photo.id,
        "status": photo.status,
        "pipeline_version": settings.pipeline_version,
    }


def rebuild_events(db: Session, owner_id: int) -> int:
    photos = list(
        db.scalars(
            select(Photo)
            .where(Photo.owner_id == owner_id, Photo.captured_at.is_not(None))
            .order_by(Photo.captured_at.asc())
        ).all()
    )
    db.execute(delete(PhotoEvent).where(PhotoEvent.owner_id == owner_id))
    groups: list[list[Photo]] = []
    for photo in photos:
        if not groups:
            groups.append([photo])
            continue
        previous = groups[-1][-1]
        time_gap = photo.captured_at - previous.captured_at
        place_changed = (
            photo.location_name
            and previous.location_name
            and photo.location_name.casefold() != previous.location_name.casefold()
        )
        if time_gap > timedelta(hours=36) or (place_changed and time_gap > timedelta(hours=6)):
            groups.append([photo])
        else:
            groups[-1].append(photo)
    for index, group in enumerate(groups):
        first, last = group[0], group[-1]
        location = next((item.location_name for item in group if item.location_name), None)
        title = location or f"Moment {index + 1}"
        db.add(
            PhotoEvent(
                id=uuid4().hex,
                owner_id=owner_id,
                title=title,
                starts_at=first.captured_at,
                ends_at=last.captured_at,
                location_name=location,
                photo_ids=[item.id for item in group],
                pipeline_version=settings.pipeline_version,
            )
        )
    db.commit()
    return len(groups)
