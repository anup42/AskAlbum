from __future__ import annotations

import hashlib
import json
import mimetypes
import math
import re
import shutil
import time
from datetime import datetime
from pathlib import Path
from uuid import uuid4

from fastapi import HTTPException
from PIL import Image, ImageOps, UnidentifiedImageError
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from .config import settings
from .models import Photo, PlaceGazetteer, SearchTrace, User
from .schemas import Attribution, PhotoResponse


ALLOWED_MIME_TYPES = {"image/jpeg", "image/png", "image/webp"}
ALLOWED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


def photo_to_response(photo: Photo) -> PhotoResponse:
    if photo.scope == "demo":
        image_url = f"/media/demo/{photo.filename}"
        thumbnail_url = image_url
    else:
        image_url = f"/api/photos/{photo.id}/content?variant=original"
        thumbnail_url = f"/api/photos/{photo.id}/content?variant=thumbnail"
    attribution = Attribution.model_validate(photo.attribution) if photo.attribution else None
    return PhotoResponse(
        id=photo.id,
        scope=photo.scope,
        title=photo.title,
        filename=photo.filename,
        relative_path=photo.relative_path,
        image_url=image_url,
        thumbnail_url=thumbnail_url,
        width=photo.width,
        height=photo.height,
        captured_at=photo.captured_at,
        location_name=photo.location_name,
        tags=photo.tags or [],
        alt_text=photo.alt_text,
        favorite=photo.favorite,
        attribution=attribution,
    )


def visible_photo_query(user: User, scope: str):
    owner_filter = or_(Photo.scope == "demo", Photo.owner_id == user.id)
    query = select(Photo).where(owner_filter)
    if scope != "all":
        query = query.where(Photo.scope == scope)
    return query


def search_photos(db: Session, user: User, query: str, scope: str, limit: int):
    started = time.perf_counter()
    photos = list(db.scalars(visible_photo_query(user, scope)).all())
    tokens = [token for token in re.findall(r"[\w'-]+", query.casefold()) if len(token) > 1]

    def score(photo: Photo) -> tuple[int, float]:
        haystack = " ".join(
            [
                photo.title,
                photo.location_name or "",
                photo.alt_text or "",
                " ".join(photo.tags or []),
            ]
        ).casefold()
        exact = sum(5 for token in tokens if token in haystack)
        phrase = 10 if query.casefold() in haystack else 0
        recent = photo.captured_at.timestamp() if photo.captured_at else 0
        return exact + phrase, recent

    ranked = [photo for photo in sorted(photos, key=score, reverse=True) if score(photo)[0] > 0]
    ranked = ranked[:limit]
    trace = SearchTrace(
        id=uuid4().hex,
        owner_id=user.id,
        query=query,
        candidate_count=len(photos),
        matched_photo_ids=[photo.id for photo in ranked],
        elapsed_ms=round((time.perf_counter() - started) * 1000),
    )
    db.add(trace)
    db.commit()
    return ranked


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _gps_decimal(values, reference: str | bytes | None) -> float | None:
    if not values or len(values) < 3:
        return None
    degrees, minutes, seconds = (float(value) for value in values[:3])
    decimal = degrees + minutes / 60 + seconds / 3600
    normalized_reference = reference.decode("ascii", "ignore") if isinstance(reference, bytes) else reference
    if normalized_reference in {"S", "W"}:
        decimal *= -1
    return decimal


def extract_photo_metadata(image: Image.Image) -> tuple[datetime | None, float | None, float | None]:
    try:
        exif = image.getexif()
        captured_raw = exif.get(36867) or exif.get(306)
        captured_at = (
            datetime.strptime(str(captured_raw), "%Y:%m:%d %H:%M:%S")
            if captured_raw
            else None
        )
        gps = exif.get_ifd(34853) if exif.get(34853) else {}
        latitude = _gps_decimal(gps.get(2), gps.get(1))
        longitude = _gps_decimal(gps.get(4), gps.get(3))
        if latitude is not None and not -90 <= latitude <= 90:
            latitude = None
        if longitude is not None and not -180 <= longitude <= 180:
            longitude = None
        return captured_at, latitude, longitude
    except (AttributeError, KeyError, TypeError, ValueError, ZeroDivisionError):
        return None, None, None


def _haversine_km(left_lat: float, left_lon: float, right_lat: float, right_lon: float) -> float:
    earth_radius_km = 6371.0088
    lat1, lat2 = math.radians(left_lat), math.radians(right_lat)
    d_lat = lat2 - lat1
    d_lon = math.radians(right_lon - left_lon)
    value = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return 2 * earth_radius_km * math.asin(math.sqrt(value))


def offline_place_name(db: Session, latitude: float | None, longitude: float | None) -> str | None:
    if latitude is None or longitude is None:
        return None
    approximate_distance = (
        (PlaceGazetteer.latitude - latitude) * (PlaceGazetteer.latitude - latitude)
        + (PlaceGazetteer.longitude - longitude) * (PlaceGazetteer.longitude - longitude)
    )
    candidates = list(db.scalars(select(PlaceGazetteer).order_by(approximate_distance, PlaceGazetteer.population.desc()).limit(50)).all())
    if not candidates:
        return None
    closest = min(
        candidates,
        key=lambda place: _haversine_km(latitude, longitude, place.latitude, place.longitude),
    )
    if _haversine_km(latitude, longitude, closest.latitude, closest.longitude) > 100:
        return None
    return f"{closest.name}, {closest.country_code}" if closest.country_code else closest.name


def finalize_uploaded_photo(db: Session, user: User, temp_path: Path, filename: str, relative_path: str):
    suffix = Path(filename).suffix.lower()
    if suffix not in ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=415, detail="This image format is not supported yet")

    try:
        with Image.open(temp_path) as image:
            image.verify()
        with Image.open(temp_path) as image:
            captured_at, latitude, longitude = extract_photo_metadata(image)
            normalized = ImageOps.exif_transpose(image)
            width, height = normalized.size
            if width * height > 120_000_000:
                raise HTTPException(status_code=413, detail="Image dimensions are too large")
            digest = sha256_file(temp_path)
            existing = db.scalar(select(Photo).where(Photo.sha256 == digest))
            if existing:
                temp_path.unlink(missing_ok=True)
                return existing, True

            original_dir = settings.data_dir / "originals" / digest[:2]
            derived_dir = settings.data_dir / "derived" / digest[:2]
            original_dir.mkdir(parents=True, exist_ok=True)
            derived_dir.mkdir(parents=True, exist_ok=True)
            original_path = original_dir / f"{digest}{suffix}"
            thumbnail_path = derived_dir / f"{digest}_thumb.webp"
            shutil.move(str(temp_path), original_path)

            thumbnail = normalized.copy()
            thumbnail.thumbnail((960, 960))
            if thumbnail.mode not in {"RGB", "RGBA"}:
                thumbnail = thumbnail.convert("RGB")
            thumbnail.save(thumbnail_path, "WEBP", quality=84, method=6)
    except (UnidentifiedImageError, OSError) as exc:
        temp_path.unlink(missing_ok=True)
        raise HTTPException(status_code=415, detail="The selected file is not a valid image") from exc

    photo = Photo(
        id=uuid4().hex,
        owner_id=user.id,
        scope="personal",
        title=Path(filename).stem.replace("_", " ").replace("-", " ").strip().title(),
        filename=Path(filename).name,
        relative_path=relative_path,
        sha256=digest,
        content_type=mimetypes.guess_type(filename)[0] or "application/octet-stream",
        original_path=str(original_path),
        thumbnail_path=str(thumbnail_path),
        width=width,
        height=height,
        captured_at=captured_at,
        latitude=latitude,
        longitude=longitude,
        location_name=offline_place_name(db, latitude, longitude),
        tags=[],
        alt_text="Uploaded photo",
        status="ready",
    )
    db.add(photo)
    db.commit()
    db.refresh(photo)
    return photo, False


def seed_demo_library(db: Session) -> None:
    manifest_path = settings.demo_dir / "manifest.json"
    if not manifest_path.exists():
        return
    entries = json.loads(manifest_path.read_text(encoding="utf-8"))
    for item in entries:
        image_path = settings.demo_dir / "images" / item["filename"]
        if not image_path.exists():
            continue
        expected_sha = item["sha256"]
        if sha256_file(image_path) != expected_sha:
            raise RuntimeError(f"Demo image checksum mismatch: {item['filename']}")
        existing = db.get(Photo, item["id"])
        captured_at = datetime.fromisoformat(item["captured_at"]) if item.get("captured_at") else None
        payload = dict(
            owner_id=None,
            scope="demo",
            title=item["title"],
            filename=item["filename"],
            relative_path=None,
            sha256=expected_sha,
            content_type=item.get("content_type", "image/jpeg"),
            original_path=str(image_path),
            thumbnail_path=str(image_path),
            width=item["width"],
            height=item["height"],
            captured_at=captured_at,
            location_name=item.get("location_name"),
            latitude=item.get("latitude"),
            longitude=item.get("longitude"),
            tags=item.get("tags", []),
            alt_text=item["alt_text"],
            attribution={
                "title": item["title"],
                "creator": item.get("creator"),
                "source_url": item["source_url"],
                "license": item["license"],
                "license_url": item["license_url"],
            },
        )
        if existing:
            for key, value in payload.items():
                setattr(existing, key, value)
        else:
            db.add(Photo(id=item["id"], **payload))
    db.commit()
