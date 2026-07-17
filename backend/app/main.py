from __future__ import annotations

from contextlib import asynccontextmanager
import hmac
import json
from pathlib import Path
from uuid import uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.responses import StreamingResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import delete, func, select, text
from sqlalchemy.orm import Session, sessionmaker

from .auth import (
    COOKIE_NAME,
    CSRF_COOKIE_NAME,
    create_csrf_token,
    create_session_token,
    ensure_admin,
    get_current_user,
    require_developer,
    verify_password,
)
from .config import settings
from .database import Base, SessionLocal, engine, get_db
from .models import PersonCluster, Photo, PhotoArtifact, PhotoEvent, PhotoFace, UploadSession, User
from .photos import (
    _haversine_km,
    finalize_uploaded_photo,
    photo_to_response,
    seed_demo_library,
    visible_photo_query,
)
from .prompts.search_planner import deterministic_search_plan
from .search import execute_search, lexical_rank
from .security import rate_limiter
from .schemas import (
    DeveloperStatusResponse,
    EventResponse,
    LibraryStatusResponse,
    LoginRequest,
    PersonResponse,
    PersonUpdate,
    PhotoListResponse,
    PhotoResponse,
    PlaceResponse,
    PrivacyActionResponse,
    ReindexResponse,
    SearchRequest,
    SearchResponse,
    SessionResponse,
    SettingsResponse,
    SettingsUpdate,
    UploadCompleteResponse,
    UploadSessionCreate,
    UploadSessionResponse,
)
from .worker import index_photo


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.validate_production()
    settings.ensure_directories()
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        ensure_admin(db)
        seed_demo_library(db)
        pending_ids = [
            photo_id
            for photo_id in db.scalars(select(Photo.id).where(Photo.pipeline_version != settings.pipeline_version)).all()
        ]
        if settings.auto_index_on_start and settings.local_models_enabled and pending_ids:
            for pending_photo in db.scalars(select(Photo).where(Photo.id.in_(pending_ids))).all():
                pending_photo.status = "processing"
                db.add(pending_photo)
            db.commit()
    if settings.auto_index_on_start:
        for photo_id in pending_ids:
            index_photo.delay(photo_id)
    yield


app = FastAPI(
    title="AskPhotos API",
    version="0.1.0",
    description="Private photo library API. Developer diagnostics use separate authorized endpoints.",
    lifespan=lifespan,
    docs_url=None if settings.env.lower() == "production" else "/docs",
    redoc_url=None if settings.env.lower() == "production" else "/redoc",
    openapi_url=None if settings.env.lower() == "production" else "/openapi.json",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://127.0.0.1:5173", "http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Content-Type", "Upload-Offset", "X-CSRF-Token"],
)


@app.middleware("http")
async def csrf_protection(request: Request, call_next):
    if request.method in {"POST", "PUT", "PATCH", "DELETE"} and request.url.path != "/api/auth/login":
        if request.cookies.get(COOKIE_NAME):
            cookie_token = request.cookies.get(CSRF_COOKIE_NAME, "")
            header_token = request.headers.get("X-CSRF-Token", "")
            if not cookie_token or not header_token or not hmac.compare_digest(cookie_token, header_token):
                return JSONResponse(
                    status_code=403,
                    content={"detail": "Your session could not be verified. Refresh the page and try again."},
                )
    return await call_next(request)


@app.middleware("http")
async def rate_limit_requests(request: Request, call_next):
    limits = {
        "/api/auth/login": 10,
        "/api/search": settings.rate_limit_per_minute,
        "/api/search/stream": settings.rate_limit_per_minute,
        "/api/uploads/sessions": 60,
    }
    if request.method in {"POST", "PUT", "PATCH"}:
        matched = next((path for path in limits if request.url.path.startswith(path)), None)
        if matched:
            forwarded = request.headers.get("x-forwarded-for", "")
            identity = forwarded.split(",", 1)[0].strip() if settings.trusted_proxy_count and forwarded else ""
            identity = identity or (request.client.host if request.client else "unknown")
            if not rate_limiter.allowed(identity, matched, limits[matched]):
                return JSONResponse(
                    status_code=429,
                    content={"detail": "Too many requests. Wait a moment and try again."},
                    headers={"Retry-After": "60"},
                )
    return await call_next(request)

demo_images = settings.demo_dir / "images"
demo_images.mkdir(parents=True, exist_ok=True)
app.mount("/media/demo", StaticFiles(directory=demo_images), name="demo-media")


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/auth/login", response_model=SessionResponse)
def login(payload: LoginRequest, response: Response, db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.username == payload.username))
    if not user or not verify_password(payload.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Username or password is incorrect")
    csrf_token = create_csrf_token()
    response.set_cookie(
        COOKIE_NAME,
        create_session_token(user),
        httponly=True,
        secure=settings.cookie_secure,
        samesite="strict",
        max_age=settings.session_days * 86400,
        path="/",
    )
    response.set_cookie(
        CSRF_COOKIE_NAME,
        csrf_token,
        httponly=False,
        secure=settings.cookie_secure,
        samesite="strict",
        max_age=settings.session_days * 86400,
        path="/",
    )
    return SessionResponse(
        authenticated=True,
        username=user.username,
        is_admin=user.is_admin,
        developer_mode=user.developer_mode,
        csrf_token=csrf_token,
    )


@app.post("/api/auth/logout", status_code=204)
def logout(response: Response):
    response.delete_cookie(COOKIE_NAME, path="/")
    response.delete_cookie(CSRF_COOKIE_NAME, path="/")


@app.get("/api/auth/session", response_model=SessionResponse)
def session(request: Request, response: Response, user: User = Depends(get_current_user)):
    csrf_token = request.cookies.get(CSRF_COOKIE_NAME) or create_csrf_token()
    if not request.cookies.get(CSRF_COOKIE_NAME):
        response.set_cookie(
            CSRF_COOKIE_NAME,
            csrf_token,
            httponly=False,
            secure=settings.cookie_secure,
            samesite="strict",
            max_age=settings.session_days * 86400,
            path="/",
        )
    return SessionResponse(
        authenticated=True,
        username=user.username,
        is_admin=user.is_admin,
        developer_mode=user.developer_mode,
        csrf_token=csrf_token,
    )


@app.get("/api/photos", response_model=PhotoListResponse)
def list_photos(
    scope: str = "all",
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if scope not in {"demo", "personal", "all"}:
        raise HTTPException(status_code=422, detail="Unknown library scope")
    photos = list(db.scalars(visible_photo_query(user, scope).order_by(Photo.captured_at.desc())).all())
    return PhotoListResponse(items=[photo_to_response(photo) for photo in photos], total=len(photos), scope=scope)


@app.get("/api/photos/{photo_id}", response_model=PhotoResponse)
def get_photo(photo_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    photo = db.get(Photo, photo_id)
    if not photo or (photo.scope != "demo" and photo.owner_id != user.id):
        raise HTTPException(status_code=404, detail="Photo not found")
    return photo_to_response(photo)


@app.get("/api/photos/{photo_id}/content")
def photo_content(
    photo_id: str,
    variant: str = "thumbnail",
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    photo = db.get(Photo, photo_id)
    if not photo or photo.scope == "demo" or photo.owner_id != user.id:
        raise HTTPException(status_code=404, detail="Photo not found")
    path = Path(photo.original_path if variant == "original" else photo.thumbnail_path)
    if not path.exists():
        raise HTTPException(status_code=404, detail="Photo file is unavailable")
    return FileResponse(path, media_type=photo.content_type if variant == "original" else "image/webp")


@app.post("/api/search", response_model=SearchResponse)
def search(payload: SearchRequest, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    result = execute_search(db, user, payload.query, payload.scope, payload.limit)
    return SearchResponse(
        query=payload.query,
        summary=result.answer,
        evidence_photo_ids=[photo.id for photo in result.final],
        items=[photo_to_response(photo) for photo in result.final],
    )


def sse_event(event: str, payload: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, separators=(',', ':'))}\n\n"


@app.post("/api/search/stream")
def stream_search(
    payload: SearchRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    user_id = user.id

    def generate():
        with sessionmaker(bind=db.get_bind(), expire_on_commit=False)() as stream_db:
            stream_user = stream_db.get(User, user_id)
            if stream_user is None:
                yield sse_event("error", {"message": "Your session has expired."})
                return
            initial_plan = deterministic_search_plan(payload.query, payload.limit)
            initial, _ = lexical_rank(stream_db, stream_user, payload.scope, initial_plan)
            initial_response = SearchResponse(
                query=payload.query,
                summary=(
                    f"I found {len(initial)} likely match{'es' if len(initial) != 1 else ''}."
                    if initial
                    else "I am checking the visual index for a closer match."
                ),
                evidence_photo_ids=[photo.id for photo in initial],
                items=[photo_to_response(photo) for photo in initial],
            )
            yield sse_event("results", initial_response.model_dump(mode="json"))
            yield sse_event("progress", {"message": "Checking the closest visual matches…"})
            try:
                result = execute_search(
                    stream_db,
                    stream_user,
                    payload.query,
                    payload.scope,
                    payload.limit,
                    include_semantic=True,
                )
                final_response = SearchResponse(
                    query=payload.query,
                    summary=result.answer,
                    evidence_photo_ids=[photo.id for photo in result.final],
                    items=[photo_to_response(photo) for photo in result.final],
                )
                yield sse_event("answer", final_response.model_dump(mode="json"))
                yield sse_event("done", {"ok": True})
            except Exception:
                yield sse_event(
                    "partial",
                    {
                        "message": (
                            "The deeper search is temporarily unavailable. "
                            "You can still use the matches already shown."
                        )
                    },
                )
                yield sse_event("done", {"ok": False})

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache, no-transform", "X-Accel-Buffering": "no"},
    )


@app.get("/api/library/status", response_model=LibraryStatusResponse)
def library_status(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    photos = list(db.scalars(visible_photo_query(user, "all")).all())
    return LibraryStatusResponse(
        total=len(photos),
        searchable=len(photos),
        getting_ready=sum(photo.status not in {"ready", "partial"} for photo in photos),
        needs_attention=sum(photo.status == "partial" for photo in photos),
    )


@app.get("/api/settings", response_model=SettingsResponse)
def get_settings(user: User = Depends(get_current_user)):
    return SettingsResponse(
        developer_mode=user.developer_mode,
        developer_feature_available=settings.developer_feature_enabled and user.is_admin,
        face_indexing_enabled=user.face_indexing_enabled,
    )


@app.put("/api/settings", response_model=SettingsResponse)
def update_settings(
    payload: SettingsUpdate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if payload.developer_mode and (not user.is_admin or not settings.developer_feature_enabled):
        raise HTTPException(status_code=403, detail="Developer mode is unavailable")
    if payload.developer_mode is not None:
        user.developer_mode = payload.developer_mode
    if payload.face_indexing_enabled is not None:
        user.face_indexing_enabled = payload.face_indexing_enabled
    db.add(user)
    db.commit()
    if payload.face_indexing_enabled:
        personal_ids = db.scalars(select(Photo.id).where(Photo.owner_id == user.id)).all()
        for photo_id in personal_ids:
            index_photo.delay(photo_id)
    return SettingsResponse(
        developer_mode=user.developer_mode,
        developer_feature_available=settings.developer_feature_enabled and user.is_admin,
        face_indexing_enabled=user.face_indexing_enabled,
    )


@app.get("/api/developer/status", response_model=DeveloperStatusResponse)
def developer_status(_: User = Depends(require_developer)):
    return DeveloperStatusResponse(
        environment=settings.env,
        database="postgresql" if settings.database_url.startswith("postgresql") else "sqlite-local",
        queue_mode="eager-local" if settings.celery_always_eager else "redis-worker",
        pipeline_version=settings.pipeline_version,
        model_profile="local-only GPU services",
    )


@app.post("/api/developer/reindex", response_model=ReindexResponse)
def reindex_library(
    _: User = Depends(require_developer),
    db: Session = Depends(get_db),
):
    photo_ids = list(db.scalars(select(Photo.id)).all())
    for photo_id in photo_ids:
        index_photo.delay(photo_id, True)
    return ReindexResponse(
        accepted=len(photo_ids),
        message=f"{len(photo_ids)} photos were queued for local re-indexing.",
    )


@app.get("/api/people", response_model=list[PersonResponse])
def list_people(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if not user.face_indexing_enabled:
        return []
    clusters = list(
        db.scalars(
            select(PersonCluster)
            .where(PersonCluster.owner_id == user.id, PersonCluster.hidden.is_(False))
            .order_by(PersonCluster.name.asc().nulls_last(), PersonCluster.sample_count.desc())
        ).all()
    )
    output: list[PersonResponse] = []
    for cluster in clusters:
        sample = db.scalar(
            select(PhotoFace.photo_id).where(PhotoFace.cluster_id == cluster.id).order_by(PhotoFace.id.asc())
        )
        output.append(
            PersonResponse(
                id=cluster.id,
                name=cluster.name,
                photo_count=cluster.sample_count,
                sample_photo_id=sample,
            )
        )
    return output


@app.patch("/api/people/{person_id}", response_model=PersonResponse)
def name_person(
    person_id: str,
    payload: PersonUpdate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    cluster = db.get(PersonCluster, person_id)
    if not cluster or cluster.owner_id != user.id or cluster.hidden:
        raise HTTPException(status_code=404, detail="Person group not found")
    cluster.name = " ".join(payload.name.split())
    db.add(cluster)
    db.commit()
    sample = db.scalar(select(PhotoFace.photo_id).where(PhotoFace.cluster_id == cluster.id))
    return PersonResponse(
        id=cluster.id,
        name=cluster.name,
        photo_count=cluster.sample_count,
        sample_photo_id=sample,
    )


@app.delete("/api/privacy/faces", response_model=PrivacyActionResponse)
def remove_face_index(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    face_ids = db.scalars(select(PhotoFace.id).where(PhotoFace.owner_id == user.id)).all()
    db.execute(delete(PhotoFace).where(PhotoFace.owner_id == user.id))
    db.execute(delete(PersonCluster).where(PersonCluster.owner_id == user.id))
    owned_photo_ids = list(db.scalars(select(Photo.id).where(Photo.owner_id == user.id)).all())
    if owned_photo_ids:
        db.execute(
            delete(PhotoArtifact).where(
                PhotoArtifact.photo_id.in_(owned_photo_ids),
                PhotoArtifact.artifact_kind == "faces",
            )
        )
    user.face_indexing_enabled = False
    db.add(user)
    db.commit()
    return PrivacyActionResponse(removed=len(face_ids))


@app.get("/api/events", response_model=list[EventResponse])
def list_events(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    events = list(
        db.scalars(
            select(PhotoEvent).where(PhotoEvent.owner_id == user.id).order_by(PhotoEvent.starts_at.desc())
        ).all()
    )
    return [
        EventResponse(
            id=event.id,
            title=event.title,
            starts_at=event.starts_at,
            ends_at=event.ends_at,
            location_name=event.location_name,
            photo_ids=event.photo_ids,
        )
        for event in events
    ]


@app.get("/api/places", response_model=list[PlaceResponse])
def list_places(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    photos = list(
        db.scalars(
            visible_photo_query(user, "all")
            .where(Photo.location_name.is_not(None))
            .order_by(Photo.captured_at.desc())
        ).all()
    )
    grouped: dict[str, list[Photo]] = {}
    for photo in photos:
        grouped.setdefault(photo.location_name or "Unknown place", []).append(photo)
    return [
        PlaceResponse(
            name=name,
            photo_count=len(items),
            latitude=next((item.latitude for item in items if item.latitude is not None), None),
            longitude=next((item.longitude for item in items if item.longitude is not None), None),
            sample_photo_id=items[0].id,
        )
        for name, items in sorted(grouped.items(), key=lambda pair: len(pair[1]), reverse=True)
    ]


@app.get("/api/places/nearby", response_model=PhotoListResponse)
def nearby_photos(
    latitude: float = Query(ge=-90, le=90),
    longitude: float = Query(ge=-180, le=180),
    radius_km: float = Query(default=10, gt=0, le=500),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if db.get_bind().dialect.name == "postgresql":
        statement = (
            visible_photo_query(user, "all")
            .where(
                text(
                    "ST_DWithin("
                    "location, "
                    "CAST(ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326) AS geography), "
                    ":radius_meters)"
                )
            )
            .params(
                latitude=latitude,
                longitude=longitude,
                radius_meters=radius_km * 1000,
            )
            .order_by(Photo.captured_at.desc())
        )
        photos = list(db.scalars(statement).all())
    else:
        candidates = list(
            db.scalars(
                visible_photo_query(user, "all").where(
                    Photo.latitude.is_not(None),
                    Photo.longitude.is_not(None),
                )
            ).all()
        )
        photos = [
            photo
            for photo in candidates
            if _haversine_km(latitude, longitude, photo.latitude, photo.longitude) <= radius_km
        ]
    return PhotoListResponse(
        items=[photo_to_response(photo) for photo in photos],
        total=len(photos),
        scope="all",
    )


def upload_to_response(upload: UploadSession) -> UploadSessionResponse:
    return UploadSessionResponse(
        id=upload.id,
        filename=upload.filename,
        relative_path=upload.relative_path,
        offset=upload.offset,
        size=upload.expected_size,
        chunk_size=settings.upload_chunk_bytes,
        completed=upload.completed,
    )


@app.post("/api/uploads/sessions", response_model=UploadSessionResponse)
def create_upload(
    payload: UploadSessionCreate,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if payload.size > settings.max_upload_bytes:
        raise HTTPException(status_code=413, detail="This file is larger than the configured upload limit")
    upload_id = uuid4().hex
    temp_path = settings.data_dir / "uploads" / f"{upload_id}.part"
    temp_path.touch(exist_ok=False)
    upload = UploadSession(
        id=upload_id,
        owner_id=user.id,
        filename=Path(payload.filename).name,
        relative_path=payload.relative_path,
        content_type=payload.content_type,
        expected_size=payload.size,
        offset=0,
        temp_path=str(temp_path),
    )
    db.add(upload)
    db.commit()
    return upload_to_response(upload)


@app.get("/api/uploads/sessions/{upload_id}", response_model=UploadSessionResponse)
def get_upload(upload_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    upload = db.get(UploadSession, upload_id)
    if not upload or upload.owner_id != user.id:
        raise HTTPException(status_code=404, detail="Upload not found")
    return upload_to_response(upload)


@app.patch("/api/uploads/sessions/{upload_id}", response_model=UploadSessionResponse)
async def append_upload(
    upload_id: str,
    request: Request,
    upload_offset: int = Header(alias="Upload-Offset"),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    upload = db.get(UploadSession, upload_id)
    if not upload or upload.owner_id != user.id or upload.completed:
        raise HTTPException(status_code=404, detail="Upload not found")
    if upload.offset != upload_offset:
        raise HTTPException(status_code=409, detail="Upload offset does not match", headers={"Upload-Offset": str(upload.offset)})
    chunk = await request.body()
    if not chunk or len(chunk) > settings.upload_chunk_bytes:
        raise HTTPException(status_code=400, detail="Upload chunk is empty or too large")
    if upload.offset + len(chunk) > upload.expected_size:
        raise HTTPException(status_code=413, detail="Upload exceeds declared file size")
    with Path(upload.temp_path).open("ab") as handle:
        handle.write(chunk)
    upload.offset += len(chunk)
    db.add(upload)
    db.commit()
    return upload_to_response(upload)


@app.post("/api/uploads/sessions/{upload_id}/complete", response_model=UploadCompleteResponse)
def complete_upload(
    upload_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    upload = db.get(UploadSession, upload_id)
    if not upload or upload.owner_id != user.id or upload.completed:
        raise HTTPException(status_code=404, detail="Upload not found")
    if upload.offset != upload.expected_size:
        raise HTTPException(status_code=409, detail="Upload is not complete")
    photo, duplicate = finalize_uploaded_photo(
        db,
        user,
        Path(upload.temp_path),
        upload.filename,
        upload.relative_path,
    )
    upload.completed = True
    if settings.local_models_enabled:
        photo.status = "processing"
        db.add(photo)
    db.add(upload)
    db.commit()
    index_photo.delay(photo.id)
    return UploadCompleteResponse(photo=photo_to_response(photo), duplicate=duplicate)
