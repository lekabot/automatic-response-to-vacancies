"""Multi-tenant vacancies_seen: composite PK (chat_id, vacancy_id).

Revision ID: 004
Revises: 003
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "004"
down_revision: Union[str, None] = "003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    conn = op.get_bind()
    is_sqlite = conn.dialect.name == "sqlite"

    if is_sqlite:
        op.execute("PRAGMA foreign_keys=OFF")
        op.create_table(
            "vacancies_seen_mt",
            sa.Column("chat_id", sa.Integer(), nullable=False),
            sa.Column("vacancy_id", sa.String(64), nullable=False),
            sa.Column("title", sa.Text(), nullable=False),
            sa.Column("employer", sa.Text(), nullable=False, server_default=""),
            sa.Column("url", sa.Text(), nullable=False, server_default=""),
            sa.Column("salary_text", sa.Text(), nullable=True),
            sa.Column("status", sa.String(32), nullable=False),
            sa.Column("seen_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
            sa.Column("attempt_count", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("last_error", sa.Text(), nullable=True),
            sa.Column("last_attempt_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("next_retry_at", sa.DateTime(timezone=True), nullable=True),
            sa.Column("processing_started_at", sa.DateTime(timezone=True), nullable=True),
            sa.PrimaryKeyConstraint("chat_id", "vacancy_id", name="pk_vacancies_seen_mt"),
        )
        op.execute(
            """
            INSERT INTO vacancies_seen_mt (
                chat_id, vacancy_id, title, employer, url, salary_text, status, seen_at,
                attempt_count, last_error, last_attempt_at, next_retry_at, processing_started_at
            )
            SELECT
                0, vacancy_id, title, employer, url, salary_text, status, seen_at,
                COALESCE(attempt_count, 0), last_error, last_attempt_at, next_retry_at, processing_started_at
            FROM vacancies_seen
            """
        )
        op.drop_table("vacancies_seen")
        op.execute("ALTER TABLE vacancies_seen_mt RENAME TO vacancies_seen")
        op.execute("PRAGMA foreign_keys=ON")
    else:
        op.add_column("vacancies_seen", sa.Column("chat_id", sa.Integer(), nullable=True))
        op.execute("UPDATE vacancies_seen SET chat_id = 0 WHERE chat_id IS NULL")
        op.alter_column("vacancies_seen", "chat_id", nullable=False)
        op.drop_constraint(op.f("vacancies_seen_pkey"), "vacancies_seen", type_="primary")
        op.create_primary_key("pk_vacancies_seen", "vacancies_seen", ["chat_id", "vacancy_id"])

    op.create_index("ix_vacancies_seen_chat_status", "vacancies_seen", ["chat_id", "status"])
    op.create_index("ix_vacancies_seen_chat_seen_at", "vacancies_seen", ["chat_id", "seen_at"])
    op.create_index("ix_vacancies_seen_chat_next_retry", "vacancies_seen", ["chat_id", "next_retry_at"])
    op.create_index(
        "ix_vacancies_seen_chat_processing", "vacancies_seen", ["chat_id", "processing_started_at"]
    )


def downgrade() -> None:
    raise NotImplementedError("Downgrade would lose multi-tenant data separation")
