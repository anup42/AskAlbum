from __future__ import annotations

import json
import math
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import settings
from .model_client import LocalModelClient, LocalModelUnavailable
from .models import PersonCluster, Photo, PhotoArtifact, PhotoEmbedding, PhotoFace, SearchTrace, User
from .photos import visible_photo_query
from .prompts.search_planner import (
    CandidateEvidence,
    SearchPlan,
    SearchPlannerInput,
    build_grounded_answer_prompt,
    build_search_plan_prompt,
    build_visual_verify_prompt,
    deterministic_search_plan,
    parse_search_plan,
    parse_visual_verification,
    validate_grounded_answer,
)


@dataclass
class SearchExecution:
    plan: SearchPlan
    initial: list[Photo]
    final: list[Photo]
    answer: str


CONTEXT_REFERENCE = re.compile(r"\b(that|those|there|same|earlier|previous)\b", re.IGNORECASE)


def previous_search_query(db: Session, user: User) -> str | None:
    return db.scalar(
        select(SearchTrace.query)
        .where(SearchTrace.owner_id == user.id)
        .order_by(SearchTrace.created_at.desc())
        .limit(1)
    )


def resolve_context(db: Session, user: User, query: str) -> tuple[str, str | None, bool]:
    if not CONTEXT_REFERENCE.search(query):
        return query, None, False
    previous = previous_search_query(db, user)
    if not previous:
        return query, None, True
    return f"{previous}. {query}", previous, False


def plan_search(query: str, limit: int, previous_query: str | None = None) -> SearchPlan:
    fallback = deterministic_search_plan(
        f"{previous_query}. {query}" if previous_query else query,
        limit,
    )
    if not settings.local_models_enabled:
        return fallback
    prompt = build_search_plan_prompt(
        SearchPlannerInput(
            query=query,
            previous_query=previous_query,
            today=datetime.now(timezone.utc).date(),
            requested_limit=limit,
        )
    )
    try:
        raw = LocalModelClient().qwen_text(prompt, max_tokens=500, temperature=0.0)
        return parse_search_plan(raw, requested_limit=limit)
    except (LocalModelUnavailable, ValueError):
        return fallback


def artifact_payloads(db: Session, photo_ids: list[str]) -> dict[tuple[str, str], dict]:
    if not photo_ids:
        return {}
    rows = db.scalars(
        select(PhotoArtifact).where(
            PhotoArtifact.photo_id.in_(photo_ids),
            PhotoArtifact.pipeline_version == settings.pipeline_version,
            PhotoArtifact.status == "ready",
            PhotoArtifact.artifact_kind.in_(["ocr", "caption", "transcript"]),
        )
    ).all()
    return {(row.photo_id, row.artifact_kind): row.payload or {} for row in rows}


def named_people_by_photo(db: Session, user: User, photo_ids: list[str]) -> dict[str, set[str]]:
    if not user.face_indexing_enabled or not photo_ids:
        return {}
    rows = db.execute(
        select(PhotoFace.photo_id, PersonCluster.name)
        .join(PersonCluster, PersonCluster.id == PhotoFace.cluster_id)
        .where(
            PhotoFace.owner_id == user.id,
            PhotoFace.photo_id.in_(photo_ids),
            PersonCluster.name.is_not(None),
            PersonCluster.hidden.is_(False),
        )
    ).all()
    output: dict[str, set[str]] = {}
    for photo_id, name in rows:
        if name:
            output.setdefault(photo_id, set()).add(name)
    return output


def lexical_rank(
    db: Session,
    user: User,
    scope: str,
    plan: SearchPlan,
) -> tuple[list[Photo], dict[tuple[str, str], dict]]:
    photos = list(db.scalars(visible_photo_query(user, scope)).all())
    photo_ids = [photo.id for photo in photos]
    artifacts = artifact_payloads(db, photo_ids)
    people = named_people_by_photo(db, user, photo_ids)
    query_tokens = [
        token
        for token in re.findall(r"[\w'-]+", " ".join([plan.semantic_text, *plan.required_terms]).casefold())
        if len(token) > 1
    ]

    def score(photo: Photo) -> tuple[float, float]:
        if plan.date_from and (not photo.captured_at or photo.captured_at.date() < plan.date_from):
            return -1.0, 0.0
        if plan.date_to and (not photo.captured_at or photo.captured_at.date() > plan.date_to):
            return -1.0, 0.0
        if plan.place and plan.place.casefold() not in (photo.location_name or "").casefold():
            return -1.0, 0.0
        photo_people = {value.casefold() for value in people.get(photo.id, set())}
        if plan.people and not all(person.casefold() in photo_people for person in plan.people):
            return -1.0, 0.0

        ocr = str(artifacts.get((photo.id, "ocr"), {}).get("text", ""))
        transcript = str(artifacts.get((photo.id, "transcript"), {}).get("text", ""))
        caption_payload = artifacts.get((photo.id, "caption"), {})
        caption = str(caption_payload.get("caption", ""))
        model_tags = " ".join(str(value) for value in caption_payload.get("tags", []))
        metadata = " ".join(
            [
                photo.title,
                photo.filename,
                photo.location_name or "",
                photo.alt_text or "",
                " ".join(photo.tags or []),
                caption,
                model_tags,
                " ".join(people.get(photo.id, set())),
            ]
        ).casefold()
        ocr_haystack = f"{ocr} {transcript}".casefold()
        value = sum(3.0 for token in query_tokens if token in metadata)
        value += sum(5.0 for term in plan.ocr_terms if term.casefold() in ocr_haystack)
        value += sum(2.0 for token in query_tokens if token in ocr_haystack)
        if plan.semantic_text.casefold() and plan.semantic_text.casefold() in metadata:
            value += 8.0
        recent = photo.captured_at.timestamp() if photo.captured_at else 0.0
        return value, recent

    ranked = [photo for photo in sorted(photos, key=score, reverse=True) if score(photo)[0] > 0]
    return ranked[: plan.limit], artifacts


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if not left or len(left) != len(right):
        return -1.0
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    return dot / (left_norm * right_norm) if left_norm and right_norm else -1.0


def vector_rank(db: Session, user: User, scope: str, query: str, limit: int) -> list[Photo]:
    if not settings.local_models_enabled or not query.strip():
        return []
    try:
        query_vector = LocalModelClient().embed_text(query)
    except LocalModelUnavailable:
        return []
    if db.get_bind().dialect.name == "postgresql":
        distance = PhotoEmbedding.embedding.cosine_distance(query_vector)
        statement = (
            visible_photo_query(user, scope)
            .join(PhotoEmbedding, PhotoEmbedding.photo_id == Photo.id)
            .where(PhotoEmbedding.pipeline_version == settings.pipeline_version)
            .order_by(distance.asc())
            .limit(limit)
        )
        return list(db.scalars(statement).all())
    visible = list(db.scalars(visible_photo_query(user, scope)).all())
    visible_by_id = {photo.id: photo for photo in visible}
    if not visible_by_id:
        return []
    rows = list(
        db.scalars(
            select(PhotoEmbedding).where(
                PhotoEmbedding.photo_id.in_(list(visible_by_id)),
                PhotoEmbedding.pipeline_version == settings.pipeline_version,
            )
        ).all()
    )
    ranked = sorted(
        rows,
        key=lambda row: cosine_similarity(list(row.embedding), query_vector),
        reverse=True,
    )
    return [visible_by_id[row.photo_id] for row in ranked[:limit] if row.photo_id in visible_by_id]


def reciprocal_rank_fusion(lexical: list[Photo], semantic: list[Photo], limit: int) -> list[Photo]:
    scores: dict[str, float] = {}
    photos: dict[str, Photo] = {}
    for collection, weight in ((lexical, 1.25), (semantic, 1.0)):
        for rank, photo in enumerate(collection, start=1):
            photos[photo.id] = photo
            scores[photo.id] = scores.get(photo.id, 0.0) + weight / (60 + rank)
    return [photos[photo_id] for photo_id in sorted(scores, key=scores.get, reverse=True)[:limit]]


def visual_verify(query: str, photos: list[Photo]) -> list[Photo]:
    limit = min(settings.visual_verification_limit, len(photos))
    if not settings.local_models_enabled or limit <= 0:
        return photos
    client = LocalModelClient()
    prompt = build_visual_verify_prompt(query)

    def check(photo: Photo) -> tuple[str, bool]:
        path = Path(photo.thumbnail_path if Path(photo.thumbnail_path).exists() else photo.original_path)
        result = parse_visual_verification(client.qwen_image(path, prompt, max_tokens=120))
        return photo.id, result.relevant

    relevant: set[str] = set()
    checked: set[str] = set()
    with ThreadPoolExecutor(max_workers=min(3, limit)) as pool:
        futures = [pool.submit(check, photo) for photo in photos[:limit]]
        for future in as_completed(futures):
            try:
                photo_id, is_relevant = future.result()
                checked.add(photo_id)
                if is_relevant:
                    relevant.add(photo_id)
            except (LocalModelUnavailable, OSError, ValueError):
                continue
    original_order = {photo.id: index for index, photo in enumerate(photos)}
    return sorted(
        photos,
        key=lambda photo: (
            0 if photo.id in relevant else 1 if photo.id not in checked else 2,
            original_order[photo.id],
        ),
    )


def candidate_evidence(photos: list[Photo], artifacts: dict[tuple[str, str], dict]) -> list[CandidateEvidence]:
    output: list[CandidateEvidence] = []
    for photo in photos:
        caption = artifacts.get((photo.id, "caption"), {})
        ocr = artifacts.get((photo.id, "ocr"), {})
        output.append(
            CandidateEvidence(
                photo_id=photo.id,
                title=photo.title,
                captured_at=photo.captured_at.isoformat() if photo.captured_at else None,
                location_name=photo.location_name,
                caption=str(caption.get("caption", photo.alt_text or ""))[:1000],
                ocr_text=str(ocr.get("text", ""))[:2000],
                tags=list(dict.fromkeys([*(photo.tags or []), *caption.get("tags", [])]))[:20],
            )
        )
    return output


def grounded_answer(query: str, photos: list[Photo], artifacts: dict[tuple[str, str], dict]) -> str:
    if not photos:
        return "I could not find a close match. Try a place, subject, visible text, or broader date."
    evidence = candidate_evidence(photos[:12], artifacts)
    allowed = {item.photo_id for item in evidence}
    try:
        if not settings.local_models_enabled:
            raise LocalModelUnavailable("Local models are disabled")
        raw = LocalModelClient().qwen_text(
            build_grounded_answer_prompt(query, evidence),
            max_tokens=220,
            temperature=0.0,
        )
        return validate_grounded_answer(raw, allowed)
    except (LocalModelUnavailable, ValueError):
        ids = " ".join(f"[{photo.id}]" for photo in photos[:4])
        count = len(photos)
        return f"I found {count} matching photo{'s' if count != 1 else ''}. {ids}".strip()


def execute_search(
    db: Session,
    user: User,
    query: str,
    scope: str,
    limit: int,
    *,
    include_semantic: bool = True,
) -> SearchExecution:
    started = time.perf_counter()
    effective_query, previous_query, needs_clarification = resolve_context(db, user, query)
    plan = plan_search(query, limit, previous_query=previous_query)
    if needs_clarification:
        trace = SearchTrace(
            id=uuid4().hex,
            owner_id=user.id,
            query=query,
            plan=plan.model_dump(mode="json"),
            candidate_count=0,
            matched_photo_ids=[],
            elapsed_ms=round((time.perf_counter() - started) * 1000),
        )
        db.add(trace)
        db.commit()
        return SearchExecution(
            plan=plan,
            initial=[],
            final=[],
            answer="Which earlier moment do you mean? Try naming a place, date, person, or subject.",
        )
    initial, artifacts = lexical_rank(db, user, scope, plan)
    semantic = vector_rank(db, user, scope, plan.semantic_text or effective_query, plan.limit) if include_semantic else []
    final = reciprocal_rank_fusion(initial, semantic, plan.limit)
    if not final:
        final = initial
    final = visual_verify(effective_query, final)
    missing_artifact_ids = [photo.id for photo in final if (photo.id, "caption") not in artifacts]
    if missing_artifact_ids:
        artifacts.update(artifact_payloads(db, missing_artifact_ids))
    answer = grounded_answer(query, final, artifacts)
    trace = SearchTrace(
        id=uuid4().hex,
        owner_id=user.id,
        query=query,
        plan=plan.model_dump(mode="json"),
        candidate_count=len(initial) + len(semantic),
        matched_photo_ids=[photo.id for photo in final],
        elapsed_ms=round((time.perf_counter() - started) * 1000),
    )
    db.add(trace)
    db.commit()
    return SearchExecution(plan=plan, initial=initial, final=final, answer=answer)
