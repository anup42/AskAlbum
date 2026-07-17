from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pgvector.sqlalchemy import Vector
from sqlalchemy import JSON, Boolean, DateTime, Float, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(120), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    developer_mode: Mapped[bool] = mapped_column(Boolean, default=False)
    face_indexing_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Photo(Base):
    __tablename__ = "photos"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    owner_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True, index=True)
    scope: Mapped[str] = mapped_column(String(20), index=True)  # demo | personal
    title: Mapped[str] = mapped_column(String(300))
    filename: Mapped[str] = mapped_column(String(500))
    relative_path: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    sha256: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    content_type: Mapped[str] = mapped_column(String(100), default="image/jpeg")
    original_path: Mapped[str] = mapped_column(String(1200))
    thumbnail_path: Mapped[str] = mapped_column(String(1200))
    width: Mapped[int] = mapped_column(Integer, default=1)
    height: Mapped[int] = mapped_column(Integer, default=1)
    captured_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    location_name: Mapped[str | None] = mapped_column(String(250), nullable=True)
    latitude: Mapped[float | None] = mapped_column(Float, nullable=True)
    longitude: Mapped[float | None] = mapped_column(Float, nullable=True)
    tags: Mapped[list[str]] = mapped_column(JSON, default=list)
    alt_text: Mapped[str] = mapped_column(Text, default="")
    favorite: Mapped[bool] = mapped_column(Boolean, default=False)
    status: Mapped[str] = mapped_column(String(30), default="ready")
    pipeline_version: Mapped[str] = mapped_column(String(50), default="foundation-v1")
    attribution: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class UploadSession(Base):
    __tablename__ = "upload_sessions"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    filename: Mapped[str] = mapped_column(String(500))
    relative_path: Mapped[str] = mapped_column(String(1000), default="")
    content_type: Mapped[str] = mapped_column(String(100))
    expected_size: Mapped[int] = mapped_column(Integer)
    offset: Mapped[int] = mapped_column(Integer, default=0)
    temp_path: Mapped[str] = mapped_column(String(1200))
    completed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class SearchTrace(Base):
    __tablename__ = "search_traces"
    __table_args__ = (UniqueConstraint("id", "owner_id"),)

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    query: Mapped[str] = mapped_column(String(1000))
    plan: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    candidate_count: Mapped[int] = mapped_column(Integer)
    matched_photo_ids: Mapped[list[str]] = mapped_column(JSON, default=list)
    elapsed_ms: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class PhotoArtifact(Base):
    __tablename__ = "photo_artifacts"
    __table_args__ = (
        UniqueConstraint("photo_id", "artifact_kind", "pipeline_version", name="uq_photo_artifact_version"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    photo_id: Mapped[str] = mapped_column(ForeignKey("photos.id", ondelete="CASCADE"), index=True)
    artifact_kind: Mapped[str] = mapped_column(String(40), index=True)
    pipeline_version: Mapped[str] = mapped_column(String(80), index=True)
    status: Mapped[str] = mapped_column(String(20), default="pending", index=True)
    payload: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    error_code: Mapped[str | None] = mapped_column(String(80), nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class PhotoEmbedding(Base):
    __tablename__ = "photo_embeddings"
    __table_args__ = (
        UniqueConstraint("photo_id", "pipeline_version", name="uq_photo_embedding_version"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    photo_id: Mapped[str] = mapped_column(ForeignKey("photos.id", ondelete="CASCADE"), index=True)
    pipeline_version: Mapped[str] = mapped_column(String(80), index=True)
    embedding: Mapped[list[float]] = mapped_column(Vector(1152))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class PersonCluster(Base):
    __tablename__ = "person_clusters"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str | None] = mapped_column(String(160), nullable=True)
    centroid: Mapped[list[float]] = mapped_column(JSON)
    sample_count: Mapped[int] = mapped_column(Integer, default=1)
    hidden: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class PhotoFace(Base):
    __tablename__ = "photo_faces"
    __table_args__ = (
        UniqueConstraint("photo_id", "face_index", "pipeline_version", name="uq_photo_face_version"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    photo_id: Mapped[str] = mapped_column(ForeignKey("photos.id", ondelete="CASCADE"), index=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    cluster_id: Mapped[str | None] = mapped_column(
        ForeignKey("person_clusters.id", ondelete="SET NULL"), nullable=True, index=True
    )
    face_index: Mapped[int] = mapped_column(Integer)
    box: Mapped[list[float]] = mapped_column(JSON)
    embedding: Mapped[list[float]] = mapped_column(JSON)
    pipeline_version: Mapped[str] = mapped_column(String(80), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class PhotoEvent(Base):
    __tablename__ = "photo_events"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(240))
    starts_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    ends_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    location_name: Mapped[str | None] = mapped_column(String(250), nullable=True)
    photo_ids: Mapped[list[str]] = mapped_column(JSON, default=list)
    pipeline_version: Mapped[str] = mapped_column(String(80), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class PlaceGazetteer(Base):
    __tablename__ = "place_gazetteer"
    __table_args__ = (
        UniqueConstraint("name", "country_code", "latitude", "longitude", name="uq_place_gazetteer_point"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(250), index=True)
    country_code: Mapped[str | None] = mapped_column(String(2), nullable=True, index=True)
    latitude: Mapped[float] = mapped_column(Float, index=True)
    longitude: Mapped[float] = mapped_column(Float, index=True)
    population: Mapped[int] = mapped_column(Integer, default=0)
