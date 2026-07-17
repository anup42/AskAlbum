"""Add local multimodal search, people and event storage.

Revision ID: 0001_local_multimodal
Revises:
Create Date: 2026-07-17
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa

from app.database import Base
import app.models  # noqa: F401


revision = "0001_local_multimodal"
down_revision = None
branch_labels = None
depends_on = None


def has_column(inspector: sa.Inspector, table: str, column: str) -> bool:
    return table in inspector.get_table_names() and column in {item["name"] for item in inspector.get_columns(table)}


def upgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name == "postgresql":
        op.execute("CREATE EXTENSION IF NOT EXISTS vector")
        op.execute("CREATE EXTENSION IF NOT EXISTS postgis")

    Base.metadata.create_all(bind=bind)
    inspector = sa.inspect(bind)
    if not has_column(inspector, "users", "face_indexing_enabled"):
        op.add_column(
            "users",
            sa.Column("face_indexing_enabled", sa.Boolean(), nullable=False, server_default=sa.false()),
        )
    inspector = sa.inspect(bind)
    if not has_column(inspector, "search_traces", "plan"):
        op.add_column("search_traces", sa.Column("plan", sa.JSON(), nullable=True))

    if bind.dialect.name == "postgresql":
        op.execute(
            """
            ALTER TABLE photos
            ADD COLUMN IF NOT EXISTS location geography(Point, 4326)
            GENERATED ALWAYS AS (
              CASE WHEN latitude IS NULL OR longitude IS NULL THEN NULL
              ELSE ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography END
            ) STORED
            """
        )
        op.execute("CREATE INDEX IF NOT EXISTS ix_photos_location_gist ON photos USING GIST (location)")
        op.execute(
            """
            CREATE INDEX IF NOT EXISTS ix_photo_embeddings_cosine
            ON photo_embeddings USING hnsw (embedding vector_cosine_ops)
            """
        )


def downgrade() -> None:
    bind = op.get_bind()
    for table in ("photo_events", "photo_faces", "person_clusters", "photo_embeddings", "photo_artifacts"):
        if table in sa.inspect(bind).get_table_names():
            op.drop_table(table)
    inspector = sa.inspect(bind)
    if has_column(inspector, "search_traces", "plan"):
        op.drop_column("search_traces", "plan")
    inspector = sa.inspect(bind)
    if has_column(inspector, "users", "face_indexing_enabled"):
        op.drop_column("users", "face_indexing_enabled")
    if bind.dialect.name == "postgresql":
        op.execute("ALTER TABLE photos DROP COLUMN IF EXISTS location")

