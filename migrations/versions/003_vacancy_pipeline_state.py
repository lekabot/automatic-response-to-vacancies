"""Vacancy pipeline state machine columns and status enum expansion.

Revision ID: 003
Revises: 002
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "003"
down_revision: Union[str, None] = "002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # SQLite: add columns
    with op.batch_alter_table("vacancies_seen") as batch:
        batch.add_column(
            sa.Column("attempt_count", sa.Integer(), nullable=False, server_default="0")
        )
        batch.add_column(sa.Column("last_error", sa.Text(), nullable=True))
        batch.add_column(sa.Column("last_attempt_at", sa.DateTime(timezone=True), nullable=True))
        batch.add_column(sa.Column("next_retry_at", sa.DateTime(timezone=True), nullable=True))
        batch.add_column(
            sa.Column("processing_started_at", sa.DateTime(timezone=True), nullable=True)
        )

    # Migrate legacy statuses (001 had APPLY_FAILED; ALREADY_APPLIED may exist in app-only DBs)
    op.execute(
        sa.text(
            "UPDATE vacancies_seen SET status = 'APPLY_PERM_ERROR' "
            "WHERE status IN ('APPLY_FAILED', 'apply_failed')"
        )
    )
    op.execute(
        sa.text(
            "UPDATE vacancies_seen SET status = 'ALREADY_APPLIED' "
            "WHERE status IN ('already_applied')"
        )
    )


def downgrade() -> None:
    op.execute(
        sa.text(
            "UPDATE vacancies_seen SET status = 'APPLIED' "
            "WHERE status IN ('IN_PROGRESS', 'APPLY_TIMEOUT', 'APPLY_TEMP_ERROR', 'APPLY_PERM_ERROR')"
        )
    )
    with op.batch_alter_table("vacancies_seen") as batch:
        batch.drop_column("processing_started_at")
        batch.drop_column("next_retry_at")
        batch.drop_column("last_attempt_at")
        batch.drop_column("last_error")
        batch.drop_column("attempt_count")
