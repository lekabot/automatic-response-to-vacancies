"""Async SQLAlchemy engine, session factory, repository helpers."""
from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import AsyncGenerator

import structlog
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from src.models import ActionLog, Base, Run, VacancySeen, VacancyStatus

log = structlog.get_logger(__name__)

_engine = None
_session_factory: async_sessionmaker[AsyncSession] | None = None


def init_db(db_url: str) -> None:
    """Инициализация движка — вызывается один раз при старте."""
    global _engine, _session_factory
    _engine = create_async_engine(
        db_url,
        echo=False,
        connect_args={"check_same_thread": False},
    )
    _session_factory = async_sessionmaker(_engine, expire_on_commit=False)
    log.info("database.init", url=db_url)


async def create_tables() -> None:
    """Создаёт таблицы (используется только без Alembic / в тестах)."""
    assert _engine is not None, "init_db() not called"
    async with _engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


@asynccontextmanager
async def get_session() -> AsyncGenerator[AsyncSession, None]:
    assert _session_factory is not None, "init_db() not called"
    async with _session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


# ---------------------------------------------------------------------------
# Repository functions
# ---------------------------------------------------------------------------


async def vacancy_already_seen(vacancy_id: str, retention_days: int) -> bool:
    """True если вакансия уже была обработана в пределах retention window."""
    from datetime import timedelta

    cutoff = datetime.now(timezone.utc) - timedelta(days=retention_days)
    async with get_session() as session:
        row = await session.get(VacancySeen, vacancy_id)
        if row is None:
            return False
        if row.first_seen_at < cutoff:
            return False
        return True


async def upsert_vacancy(
    *,
    vacancy_id: str,
    title: str,
    employer: str,
    url: str,
    apply_url: str | None,
    salary_text: str | None,
    status: VacancyStatus,
) -> VacancySeen:
    async with get_session() as session:
        row = await session.get(VacancySeen, vacancy_id)
        if row is None:
            row = VacancySeen(
                vacancy_id=vacancy_id,
                title=title,
                employer=employer,
                url=url,
                apply_url=apply_url,
                salary_text=salary_text,
                status=status,
            )
            session.add(row)
        else:
            row.last_seen_at = datetime.now(timezone.utc)
            row.status = status
        return row


async def set_vacancy_status(vacancy_id: str, status: VacancyStatus) -> None:
    async with get_session() as session:
        await session.execute(
            update(VacancySeen)
            .where(VacancySeen.vacancy_id == vacancy_id)
            .values(status=status, last_seen_at=datetime.now(timezone.utc))
        )


async def set_vacancy_message_id(vacancy_id: str, message_id: int) -> None:
    async with get_session() as session:
        await session.execute(
            update(VacancySeen)
            .where(VacancySeen.vacancy_id == vacancy_id)
            .values(message_id=message_id)
        )


async def log_action(
    action: str,
    vacancy_id: str | None = None,
    payload: dict | None = None,
) -> None:
    async with get_session() as session:
        session.add(
            ActionLog(
                vacancy_id=vacancy_id,
                action=action,
                payload_json=payload,
            )
        )


async def create_run() -> int:
    async with get_session() as session:
        run = Run()
        session.add(run)
        await session.flush()
        return run.run_id


async def finish_run(run_id: int, counts: dict) -> None:
    async with get_session() as session:
        await session.execute(
            update(Run)
            .where(Run.run_id == run_id)
            .values(finished_at=datetime.now(timezone.utc), counts_json=counts)
        )


async def get_today_stats() -> dict:
    """Статистика за сегодня для вечернего summary."""
    from datetime import timedelta

    cutoff = datetime.now(timezone.utc) - timedelta(hours=24)
    async with get_session() as session:
        result = await session.execute(
            select(VacancySeen).where(VacancySeen.first_seen_at >= cutoff)
        )
        rows = result.scalars().all()

    counts: dict[str, int] = {
        "sent": 0,
        "applied_confirmed": 0,
        "skipped": 0,
        "requires_test": 0,
        "new": 0,
    }
    requires_test_list: list[dict] = []

    for r in rows:
        match r.status:
            case VacancyStatus.SENT:
                counts["sent"] += 1
            case VacancyStatus.APPLIED_CONFIRMED:
                counts["applied_confirmed"] += 1
            case VacancyStatus.SKIPPED:
                counts["skipped"] += 1
            case VacancyStatus.REQUIRES_TEST:
                counts["requires_test"] += 1
                requires_test_list.append({"title": r.title, "url": r.url, "employer": r.employer})
            case VacancyStatus.NEW:
                counts["new"] += 1

    return {"counts": counts, "requires_test": requires_test_list}
