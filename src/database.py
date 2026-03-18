"""Async SQLAlchemy engine, session factory, per-user vacancy repository."""
from __future__ import annotations

import random
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Any, AsyncGenerator

import structlog
from sqlalchemy import delete as sa_delete
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from src.models import Base, UserSettings, VacancySeen, VacancyStatus

log = structlog.get_logger(__name__)

_engine = None
_session_factory: async_sessionmaker[AsyncSession] | None = None

LAST_ERROR_MAX_LEN = 1000

_TERMINAL_SKIP: frozenset[VacancyStatus] = frozenset(
    {
        VacancyStatus.APPLIED,
        VacancyStatus.ALREADY_APPLIED,
        VacancyStatus.SKIPPED,
        VacancyStatus.REQUIRES_TEST,
        VacancyStatus.APPLY_PERM_ERROR,
    }
)


def _normalize_last_error(msg: str | None) -> str | None:
    if msg is None:
        return None
    s = msg.strip()
    if len(s) <= LAST_ERROR_MAX_LEN:
        return s
    return s[: LAST_ERROR_MAX_LEN - 3] + "..."


def init_db(
    db_url: str,
    *,
    pool_size: int = 5,
    max_overflow: int = 10,
    pool_recycle_seconds: int = 3600,
) -> None:
    global _engine, _session_factory
    kw: dict[str, Any] = {"echo": False}
    low = db_url.lower()
    if "sqlite" in low:
        kw["connect_args"] = {"check_same_thread": False}
    else:
        kw["pool_pre_ping"] = True
        kw["pool_recycle"] = pool_recycle_seconds
        kw["pool_size"] = pool_size
        kw["max_overflow"] = max_overflow
    _engine = create_async_engine(db_url, **kw)
    _session_factory = async_sessionmaker(_engine, expire_on_commit=False)
    log.info("database.init", backend="sqlite" if "sqlite" in low else "pooled")


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


def _pk(chat_id: int, vacancy_id: str) -> dict[str, Any]:
    return {"chat_id": chat_id, "vacancy_id": vacancy_id}


def _aware(dt: datetime | None) -> datetime | None:
    if dt is None:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


def compute_next_retry_at(attempt_count: int, *, base_seconds: float = 60.0, max_exp: int = 6) -> datetime:
    exp = min(max(attempt_count, 1), max_exp)
    delay = base_seconds * (2 ** (exp - 1)) + random.uniform(0, 30)
    return datetime.now(timezone.utc) + timedelta(seconds=delay)


class ClaimReason:
    CLAIMED = "claimed"
    SKIP_TERMINAL = "skip_terminal"
    SKIP_BACKOFF = "skip_backoff"
    SKIP_IN_PROGRESS = "skip_in_progress"


async def try_claim_vacancy_for_processing(
    *,
    chat_id: int,
    vacancy_id: str,
    title: str,
    employer: str,
    url: str,
    salary_text: str | None,
    retention_days: int,
    lease_minutes: int,
) -> tuple[str, int]:
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=retention_days)
    lease = timedelta(minutes=lease_minutes)

    async with get_session() as session:
        row = await session.get(VacancySeen, _pk(chat_id, vacancy_id))

        if row is not None:
            seen = _aware(row.seen_at) or now
            if seen < cutoff:
                await session.delete(row)
                await session.flush()
                row = None

        if row is not None:
            st = row.status
            if st in _TERMINAL_SKIP:
                return (ClaimReason.SKIP_TERMINAL, row.attempt_count)

            if st == VacancyStatus.IN_PROGRESS:
                started = _aware(row.processing_started_at)
                if started is not None and (now - started) < lease:
                    return (ClaimReason.SKIP_IN_PROGRESS, row.attempt_count)

            if st in (VacancyStatus.APPLY_TIMEOUT, VacancyStatus.APPLY_TEMP_ERROR):
                nr = _aware(row.next_retry_at)
                if nr is not None and nr > now:
                    return (ClaimReason.SKIP_BACKOFF, row.attempt_count)

            ac = row.attempt_count + 1
            row.status = VacancyStatus.IN_PROGRESS
            row.attempt_count = ac
            row.last_attempt_at = now
            row.processing_started_at = now
            row.last_error = None
            row.next_retry_at = None
            row.title = title
            row.employer = employer
            row.url = url
            row.salary_text = salary_text
            row.seen_at = now
            return (ClaimReason.CLAIMED, ac)

        session.add(
            VacancySeen(
                chat_id=chat_id,
                vacancy_id=vacancy_id,
                title=title,
                employer=employer,
                url=url,
                salary_text=salary_text,
                status=VacancyStatus.IN_PROGRESS,
                attempt_count=1,
                last_attempt_at=now,
                processing_started_at=now,
                seen_at=now,
            )
        )
        return (ClaimReason.CLAIMED, 1)


async def persist_terminal_vacancy(
    *,
    chat_id: int,
    vacancy_id: str,
    status: VacancyStatus,
    last_error: str | None = None,
    next_retry_at: datetime | None = None,
) -> None:
    now = datetime.now(timezone.utc)
    async with get_session() as session:
        row = await session.get(VacancySeen, _pk(chat_id, vacancy_id))
        if row is None:
            log.warning(
                "database.persist_terminal.missing_row",
                chat_id=chat_id,
                vacancy_id=vacancy_id,
            )
            return
        row.status = status
        row.last_error = _normalize_last_error(last_error)
        row.next_retry_at = next_retry_at
        row.processing_started_at = None
        row.seen_at = now


async def upsert_vacancy_skip_or_test(
    *,
    chat_id: int,
    vacancy_id: str,
    title: str,
    employer: str,
    url: str,
    salary_text: str | None,
    status: VacancyStatus,
) -> None:
    now = datetime.now(timezone.utc)
    async with get_session() as session:
        row = await session.get(VacancySeen, _pk(chat_id, vacancy_id))
        if row is None:
            session.add(
                VacancySeen(
                    chat_id=chat_id,
                    vacancy_id=vacancy_id,
                    title=title,
                    employer=employer,
                    url=url,
                    salary_text=salary_text,
                    status=status,
                    attempt_count=0,
                    seen_at=now,
                )
            )
        else:
            row.title = title
            row.employer = employer
            row.url = url
            row.salary_text = salary_text
            row.status = status
            row.seen_at = now
            row.processing_started_at = None
            row.next_retry_at = None


async def vacancy_already_seen(chat_id: int, vacancy_id: str, retention_days: int) -> bool:
    cutoff = datetime.now(timezone.utc) - timedelta(days=retention_days)
    async with get_session() as session:
        row = await session.get(VacancySeen, _pk(chat_id, vacancy_id))
        if row is None:
            return False
        seen_at = _aware(row.seen_at) or cutoff
        return seen_at >= cutoff


async def upsert_vacancy(
    *,
    chat_id: int,
    vacancy_id: str,
    title: str,
    employer: str,
    url: str,
    salary_text: str | None,
    status: VacancyStatus,
) -> None:
    now = datetime.now(timezone.utc)
    async with get_session() as session:
        row = await session.get(VacancySeen, _pk(chat_id, vacancy_id))
        if row is None:
            session.add(
                VacancySeen(
                    chat_id=chat_id,
                    vacancy_id=vacancy_id,
                    title=title,
                    employer=employer,
                    url=url,
                    salary_text=salary_text,
                    status=status,
                    seen_at=now,
                )
            )
        else:
            row.title = title
            row.employer = employer
            row.url = url
            row.salary_text = salary_text
            row.status = status
            row.seen_at = now


def _cutoff_24h_naive_utc() -> datetime:
    return (datetime.now(timezone.utc) - timedelta(hours=24)).replace(tzinfo=None)


async def get_applied_today_count(chat_id: int) -> int:
    cutoff = _cutoff_24h_naive_utc()
    async with get_session() as session:
        stmt = (
            select(func.count())
            .select_from(VacancySeen)
            .where(
                VacancySeen.chat_id == chat_id,
                VacancySeen.status == VacancyStatus.APPLIED,
                VacancySeen.seen_at >= cutoff,
            )
        )
        n = await session.scalar(stmt)
        return int(n or 0)


async def get_today_stats(chat_id: int) -> dict[str, Any]:
    cutoff = _cutoff_24h_naive_utc()
    async with get_session() as session:
        agg = await session.execute(
            select(VacancySeen.status, func.count())
            .where(
                VacancySeen.chat_id == chat_id,
                VacancySeen.seen_at >= cutoff,
            )
            .group_by(VacancySeen.status)
        )
        failed_rows = await session.execute(
            select(
                VacancySeen.title,
                VacancySeen.employer,
                VacancySeen.url,
                VacancySeen.salary_text,
            ).where(
                VacancySeen.chat_id == chat_id,
                VacancySeen.seen_at >= cutoff,
                VacancySeen.status == VacancyStatus.APPLY_PERM_ERROR,
            )
        )

    counts: dict[str, int] = {s.value: 0 for s in VacancyStatus}
    for status, cnt in agg.all():
        counts[status.value] = int(cnt)

    failed_vacancies: list[dict[str, Any]] = [
        {
            "title": t,
            "employer": e,
            "url": u,
            "salary_text": sal or "з/п не указана",
        }
        for t, e, u, sal in failed_rows.all()
    ]

    applied = counts.get(VacancyStatus.APPLIED.value, 0)
    failed = counts.get(VacancyStatus.APPLY_PERM_ERROR.value, 0)
    retry_later = (
        counts.get(VacancyStatus.APPLY_TIMEOUT.value, 0)
        + counts.get(VacancyStatus.APPLY_TEMP_ERROR.value, 0)
    )

    return {
        "applied": applied,
        "failed": failed,
        "retry_later": retry_later,
        "skipped": counts.get(VacancyStatus.SKIPPED.value, 0),
        "requires_test": counts.get(VacancyStatus.REQUIRES_TEST.value, 0),
        "already_applied": counts.get(VacancyStatus.ALREADY_APPLIED.value, 0),
        "failed_vacancies": failed_vacancies,
        "counts": counts,
    }


async def reset_applied_vacancies(chat_id: int) -> int:
    async with get_session() as session:
        result = await session.execute(sa_delete(VacancySeen).where(VacancySeen.chat_id == chat_id))
        return result.rowcount  # type: ignore[union-attr]


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
