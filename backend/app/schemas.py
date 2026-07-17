from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=120)
    password: str = Field(min_length=1, max_length=300)


class SessionResponse(BaseModel):
    authenticated: bool
    username: str | None = None
    is_admin: bool = False
    developer_mode: bool = False
    csrf_token: str


class Attribution(BaseModel):
    title: str
    creator: str | None = None
    source_url: str
    license: str
    license_url: str


class PhotoResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    scope: Literal["demo", "personal"]
    title: str
    filename: str
    relative_path: str | None = None
    image_url: str
    thumbnail_url: str
    width: int
    height: int
    captured_at: datetime | None = None
    location_name: str | None = None
    tags: list[str]
    alt_text: str
    favorite: bool
    attribution: Attribution | None = None


class PhotoListResponse(BaseModel):
    items: list[PhotoResponse]
    total: int
    scope: Literal["demo", "personal", "all"]


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=1000)
    scope: Literal["demo", "personal", "all"] = "all"
    limit: int = Field(default=50, ge=1, le=100)


class SearchResponse(BaseModel):
    query: str
    summary: str
    evidence_photo_ids: list[str]
    items: list[PhotoResponse]


class SettingsResponse(BaseModel):
    developer_mode: bool
    developer_feature_available: bool
    face_indexing_enabled: bool


class SettingsUpdate(BaseModel):
    developer_mode: bool | None = None
    face_indexing_enabled: bool | None = None


class UploadSessionCreate(BaseModel):
    filename: str = Field(min_length=1, max_length=500)
    relative_path: str = Field(default="", max_length=1000)
    content_type: str = Field(default="application/octet-stream", max_length=100)
    size: int = Field(gt=0)

    @field_validator("relative_path")
    @classmethod
    def validate_relative_path(cls, value: str) -> str:
        normalized = value.replace("\\", "/").strip("/")
        parts = [part for part in normalized.split("/") if part]
        if any(part in {".", ".."} for part in parts):
            raise ValueError("relative path contains unsafe segments")
        if any(":" in part for part in parts):
            raise ValueError("absolute paths are not accepted")
        return "/".join(parts)


class UploadSessionResponse(BaseModel):
    id: str
    filename: str
    relative_path: str
    offset: int
    size: int
    chunk_size: int
    completed: bool


class UploadCompleteResponse(BaseModel):
    photo: PhotoResponse
    duplicate: bool


class DeveloperStatusResponse(BaseModel):
    environment: str
    database: str
    queue_mode: str
    pipeline_version: str
    model_profile: str


class LibraryStatusResponse(BaseModel):
    total: int
    searchable: int
    getting_ready: int
    needs_attention: int


class PersonResponse(BaseModel):
    id: str
    name: str | None = None
    photo_count: int
    sample_photo_id: str | None = None


class PersonUpdate(BaseModel):
    name: str = Field(min_length=1, max_length=160)


class EventResponse(BaseModel):
    id: str
    title: str
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    location_name: str | None = None
    photo_ids: list[str]


class PlaceResponse(BaseModel):
    name: str
    photo_count: int
    latitude: float | None = None
    longitude: float | None = None
    sample_photo_id: str


class PrivacyActionResponse(BaseModel):
    removed: int


class ReindexResponse(BaseModel):
    accepted: int
    message: str
