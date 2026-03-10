"""Initial schema: vacancies_seen + user_settings

Revision ID: 001
Revises:
Create Date: 2026-03-10 00:00:00.000000
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "vacancies_seen",
        sa.Column("vacancy_id", sa.String(64), primary_key=True),
        sa.Column("title", sa.Text, nullable=False),
        sa.Column("employer", sa.Text, nullable=False, server_default=""),
        sa.Column("url", sa.Text, nullable=False, server_default=""),
        sa.Column("salary_text", sa.Text, nullable=True),
        sa.Column(
            "status",
            sa.Enum("APPLIED", "APPLY_FAILED", "SKIPPED", "REQUIRES_TEST", name="vacancystatus"),
            nullable=False,
        ),
        sa.Column(
            "seen_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_vacancies_seen_seen_at", "vacancies_seen", ["seen_at"])
    op.create_index("ix_vacancies_seen_status", "vacancies_seen", ["status"])

    op.create_table(
        "user_settings",
        sa.Column("chat_id", sa.Integer, primary_key=True),
        sa.Column("keywords_json", sa.Text, nullable=False, server_default="[]"),
        sa.Column("cover_letter", sa.Text, nullable=True),
        sa.Column("hh_email", sa.Text, nullable=True),
        sa.Column("hh_password", sa.Text, nullable=True),
        sa.Column("resume_id", sa.Text, nullable=True),
        sa.Column("resume_title", sa.Text, nullable=True),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_table("user_settings")
    op.drop_index("ix_vacancies_seen_status", "vacancies_seen")
    op.drop_index("ix_vacancies_seen_seen_at", "vacancies_seen")
    op.drop_table("vacancies_seen")
