"""Initial schema: vacancies_seen, actions_log, runs

Revision ID: 001
Revises:
Create Date: 2024-01-01 00:00:00.000000

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
        sa.Column("vacancy_id", sa.String(32), primary_key=True),
        sa.Column("title", sa.Text, nullable=False),
        sa.Column("employer", sa.Text, nullable=False, server_default=""),
        sa.Column("url", sa.Text, nullable=False, server_default=""),
        sa.Column("apply_url", sa.Text, nullable=True),
        sa.Column("salary_text", sa.Text, nullable=True),
        sa.Column(
            "status",
            sa.Enum(
                "NEW",
                "SENT",
                "APPLIED_CONFIRMED",
                "SKIPPED",
                "REQUIRES_TEST",
                name="vacancystatus",
            ),
            nullable=False,
            server_default="NEW",
        ),
        sa.Column(
            "first_seen_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "last_seen_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("message_id", sa.Integer, nullable=True),
    )

    op.create_table(
        "actions_log",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "ts",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("vacancy_id", sa.String(32), nullable=True),
        sa.Column("action", sa.String(64), nullable=False),
        sa.Column("payload_json", sa.JSON, nullable=True),
    )

    op.create_table(
        "runs",
        sa.Column("run_id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "started_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("counts_json", sa.JSON, nullable=True),
    )

    # Индексы для частых запросов
    op.create_index("ix_vacancies_seen_status", "vacancies_seen", ["status"])
    op.create_index("ix_vacancies_seen_first_seen", "vacancies_seen", ["first_seen_at"])
    op.create_index("ix_actions_log_vacancy_id", "actions_log", ["vacancy_id"])
    op.create_index("ix_actions_log_ts", "actions_log", ["ts"])


def downgrade() -> None:
    op.drop_table("runs")
    op.drop_table("actions_log")
    op.drop_table("vacancies_seen")
