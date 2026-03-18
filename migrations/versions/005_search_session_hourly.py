"""Search session start + hourly report slot (per user).

Revision ID: 005
Revises: 004
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "005"
down_revision: Union[str, None] = "004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    with op.batch_alter_table("user_settings") as batch:
        batch.add_column(
            sa.Column("search_session_started_at", sa.DateTime(timezone=True), nullable=True)
        )
        batch.add_column(sa.Column("last_hourly_report_slot", sa.Integer(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("user_settings") as batch:
        batch.drop_column("last_hourly_report_slot")
        batch.drop_column("search_session_started_at")
