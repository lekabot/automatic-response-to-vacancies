"""Async SQLAlchemy engine, session factory, repository helpers."""
from __future__ import annotations

import json
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Any, AsyncGenerator

import structlog
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from src.models import Base, UserSettings, VacancySeen, VacancyStatus

log = structlog.get_logger(__name__)

_engine = None
_session_factory: async_sessionmaker[AsyncSession] | None = None


def init_db(db_url: str) -> None:
    global _engine, _session_factory
    _engine = create_async_engine(db_url, echo=False, connect_args={"check_same_thread": False})
    _session_factory = async_sessionmaker(_engine, expire_on_commit=False)
    log.info("database.init", url=db_url)


async def create_tables() -> None:
    assert _engine is not None
    async with _engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


@asynccontextmanager
async def get_session() -> AsyncGenerator[AsyncSession, None]:
    assert _session_factory is not None
    async with _session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


# ---------------------------------------------------------------------------
# Vacancies
# ---------------------------------------------------------------------------


async def vacancy_already_seen(vacancy_id: str, retention_days: int) -> bool:
    cutoff = datetime.now(timezone.utc) - timedelta(days=retention_days)
    async with get_session() as session:
        row = await session.get(VacancySeen, vacancy_id)
        if row is None:
            return False
        # SQLite возвращает naive datetime — приводим к UTC для сравнения
        seen_at = row.seen_at
        if seen_at.tzinfo is None:
            seen_at = seen_at.replace(tzinfo=timezone.utc)
        return seen_at >= cutoff


async def upsert_vacancy(
    *,
    vacancy_id: str,
    title: str,
    employer: str,
    url: str,
    salary_text: str | None,
    status: VacancyStatus,
) -> None:
    async with get_session() as session:
        row = await session.get(VacancySeen, vacancy_id)
        if row is None:
            session.add(
                VacancySeen(
                    vacancy_id=vacancy_id,
                    title=title,
                    employer=employer,
                    url=url,
                    salary_text=salary_text,
                    status=status,
                )
            )
        else:
            row.status = status
            row.seen_at = datetime.now(timezone.utc)


async def get_applied_today_count() -> int:
    """Количество успешных откликов за последние 24 часа."""
    # Сравниваем наивными датами, так как SQLite хранит без timezone
    cutoff = datetime.utcnow() - timedelta(hours=24)
    async with get_session() as session:
        result = await session.execute(
            select(VacancySeen).where(
                VacancySeen.status == VacancyStatus.APPLIED,
                VacancySeen.seen_at >= cutoff,
            )
        )
        return len(result.scalars().all())


async def get_today_stats() -> dict:
    """Статистика за последние 24 часа: счётчики + список неудачных откликов."""
    # Сравниваем наивными датами, так как SQLite хранит без timezone
    cutoff = datetime.utcnow() - timedelta(hours=24)
    async with get_session() as session:
        result = await session.execute(
            select(VacancySeen).where(VacancySeen.seen_at >= cutoff)
        )
        rows = result.scalars().all()

    counts = {s.value: 0 for s in VacancyStatus}
    failed_vacancies: list[dict] = []

    for row in rows:
        counts[row.status.value] += 1
        if row.status == VacancyStatus.APPLY_FAILED:
            failed_vacancies.append(
                {
                    "title": row.title,
                    "employer": row.employer,
                    "url": row.url,
                    "salary_text": row.salary_text or "з/п не указана",
                }
            )

    return {
        "applied": counts[VacancyStatus.APPLIED.value],
        "failed": counts[VacancyStatus.APPLY_FAILED.value],
        "skipped": counts[VacancyStatus.SKIPPED.value],
        "requires_test": counts[VacancyStatus.REQUIRES_TEST.value],
        "failed_vacancies": failed_vacancies,
    }


async def reset_applied_vacancies() -> int:
    """Удаляет записи об откликах из БД, чтобы бот мог откликнуться повторно.

    Returns:
        Количество удалённых записей.
    """
    from sqlalchemy import delete as sa_delete
    async with get_session() as session:
        result = await session.execute(sa_delete(VacancySeen))
        return result.rowcount


# ---------------------------------------------------------------------------
# User settings
# ---------------------------------------------------------------------------


async def get_user_settings(chat_id: int) -> UserSettings | None:
    async with get_session() as session:
        return await session.get(UserSettings, chat_id)


async def save_user_settings(chat_id: int, **updates: Any) -> UserSettings:
    async with get_session() as session:
        row = await session.get(UserSettings, chat_id)
        if row is None:
            row = UserSettings(chat_id=chat_id)
            session.add(row)
        for key, value in updates.items():
            setattr(row, key, value)
        row.updated_at = datetime.now(timezone.utc)
        return row
