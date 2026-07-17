"""Add the offline reverse-geocode gazetteer.

Revision ID: 0002_place_gazetteer
Revises: 0001_local_multimodal
Create Date: 2026-07-17
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0002_place_gazetteer"
down_revision = "0001_local_multimodal"
branch_labels = None
depends_on = None


def upgrade() -> None:
    inspector = sa.inspect(op.get_bind())
    if "place_gazetteer" in inspector.get_table_names():
        return
    op.create_table(
        "place_gazetteer",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(length=250), nullable=False),
        sa.Column("country_code", sa.String(length=2), nullable=True),
        sa.Column("latitude", sa.Float(), nullable=False),
        sa.Column("longitude", sa.Float(), nullable=False),
        sa.Column("population", sa.Integer(), nullable=False, server_default="0"),
        sa.UniqueConstraint(
            "name",
            "country_code",
            "latitude",
            "longitude",
            name="uq_place_gazetteer_point",
        ),
    )
    op.create_index("ix_place_gazetteer_name", "place_gazetteer", ["name"])
    op.create_index("ix_place_gazetteer_country_code", "place_gazetteer", ["country_code"])
    op.create_index("ix_place_gazetteer_latitude", "place_gazetteer", ["latitude"])
    op.create_index("ix_place_gazetteer_longitude", "place_gazetteer", ["longitude"])


def downgrade() -> None:
    if "place_gazetteer" in sa.inspect(op.get_bind()).get_table_names():
        op.drop_table("place_gazetteer")
