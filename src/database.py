"""Async SQLAlchemy engine, session factory, per-user vacancy repository."""
from __future__ import annotations

import random
from contextlib import asynccontextmanager
from dataclasses import dataclass
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


@dataclass(slots=True)
class ClaimDecision:
    reason: str
    attempt_count: int
    current_status: VacancyStatus | None = None
    next_retry_at: datetime | None = None


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
) -> ClaimDecision:
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
                return ClaimDecision(
                    reason=ClaimReason.SKIP_TERMINAL,
                    attempt_count=row.attempt_count,
                    current_status=st,
                    next_retry_at=_aware(row.next_retry_at),
                )

            if st == VacancyStatus.IN_PROGRESS:
                started = _aware(row.processing_started_at)
                if started is not None and (now - started) < lease:
                    claim_after = started + lease
                    return ClaimDecision(
                        reason=ClaimReason.SKIP_IN_PROGRESS,
                        attempt_count=row.attempt_count,
                        current_status=VacancyStatus.IN_PROGRESS,
                        next_retry_at=claim_after,
                    )

            if st in (VacancyStatus.APPLY_TIMEOUT, VacancyStatus.APPLY_TEMP_ERROR):
                nr = _aware(row.next_retry_at)
                if nr is not None and nr > now:
                    return ClaimDecision(
                        reason=ClaimReason.SKIP_BACKOFF,
                        attempt_count=row.attempt_count,
                        current_status=st,
                        next_retry_at=nr,
                    )

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
            return ClaimDecision(
                reason=ClaimReason.CLAIMED,
                attempt_count=ac,
                current_status=VacancyStatus.IN_PROGRESS,
                next_retry_at=None,
            )

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
        return ClaimDecision(
            reason=ClaimReason.CLAIMED,
            attempt_count=1,
            current_status=VacancyStatus.IN_PROGRESS,
            next_retry_at=None,
        )


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


async def start_search_session(chat_id: int, started_at: datetime) -> None:
    now = datetime.now(timezone.utc)
    at = _aware(started_at) or now
    async with get_session() as session:
        row = await session.get(UserSettings, chat_id)
        if row is None:
            row = UserSettings(chat_id=chat_id)
            session.add(row)
        row.search_session_started_at = at
        row.last_hourly_report_slot = None
        row.updated_at = now


async def clear_search_session(chat_id: int, *, log_event: str | None = None) -> bool:
    """
    Сбрасывает search session. Идемпотентно.
    Возвращает True, если до вызова была активная сессия (и сброс выполнен).
    """
    now = datetime.now(timezone.utc)
    had_active = False
    async with get_session() as session:
        row = await session.get(UserSettings, chat_id)
        if row is None:
            return False
        if row.search_session_started_at is not None or row.last_hourly_report_slot is not None:
            had_active = True
        if not had_active:
            return False
        row.search_session_started_at = None
        row.last_hourly_report_slot = None
        row.updated_at = now
    if log_event:
        log.info(log_event, chat_id=chat_id)
    return True


async def try_claim_current_hourly_report_slot(
    chat_id: int, now: datetime
) -> tuple[int | None, int]:
    """
    Занимает только актуальный слот current_slot = floor((now-start)/1h), без догона 1..N-1.
    Возвращает (claimed_slot, previous_last) — previous_last для отката (0 если было None).
    """
    now = _aware(now) or datetime.now(timezone.utc)
    async with get_session() as session:
        row = await session.get(UserSettings, chat_id)
        if row is None or row.search_session_started_at is None:
            return None, 0
        start = _aware(row.search_session_started_at)
        if start is None:
            return None, 0
        elapsed_sec = (now - start).total_seconds()
        current_slot = int(elapsed_sec // 3600)
        if current_slot < 1:
            return None, 0
        last = row.last_hourly_report_slot or 0
        if current_slot <= last:
            return None, last
        if current_slot > last + 1:
            log.info(
                "hourly_report.skipped_stale_slots",
                chat_id=chat_id,
                previous_last=last,
                current_slot=current_slot,
                skipped_from=last + 1,
                skipped_to=current_slot - 1,
            )
        previous_last = last
        row.last_hourly_report_slot = current_slot
        row.updated_at = now
        log.info(
            "hourly_report.claimed_current_slot",
            chat_id=chat_id,
            slot=current_slot,
            previous_last=previous_last,
        )
        return current_slot, previous_last


async def revert_hourly_report_to_previous_last(chat_id: int, previous_last: int) -> None:
    """После неудачной отправки: вернуть last_hourly_report_slot к previous_last (0 → None)."""
    async with get_session() as session:
        row = await session.get(UserSettings, chat_id)
        if row is None:
            return
        row.last_hourly_report_slot = None if previous_last < 1 else previous_last
        row.updated_at = datetime.now(timezone.utc)


async def get_session_window_stats(chat_id: int) -> dict[str, Any] | None:
    """Статистика только с момента search_session_started_at."""
    async with get_session() as session:
        us = await session.get(UserSettings, chat_id)
        if us is None or us.search_session_started_at is None:
            return None
        start = _aware(us.search_session_started_at)
        if start is None:
            return None

        agg = await session.execute(
            select(VacancySeen.status, func.count())
            .where(
                VacancySeen.chat_id == chat_id,
                VacancySeen.seen_at >= start,
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
                VacancySeen.seen_at >= start,
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


async def get_session_test_vacancies(
    chat_id: int, *, limit: int = 20
) -> tuple[list[dict[str, Any]], int]:
    """REQUIRES_TEST в окне текущей search session; (страница, всего)."""
    async with get_session() as session:
        us = await session.get(UserSettings, chat_id)
        if us is None or us.search_session_started_at is None:
            return [], 0
        start = _aware(us.search_session_started_at)
        if start is None:
            return [], 0

        total = await session.scalar(
            select(func.count())
            .select_from(VacancySeen)
            .where(
                VacancySeen.chat_id == chat_id,
                VacancySeen.seen_at >= start,
                VacancySeen.status == VacancyStatus.REQUIRES_TEST,
            )
        )
        n = int(total or 0)
        if n == 0:
            return [], 0

        rows = await session.execute(
            select(VacancySeen.title, VacancySeen.employer, VacancySeen.url)
            .where(
                VacancySeen.chat_id == chat_id,
                VacancySeen.seen_at >= start,
                VacancySeen.status == VacancyStatus.REQUIRES_TEST,
            )
            .order_by(VacancySeen.seen_at.desc())
            .limit(limit)
        )
        out = [
            {"title": t, "employer": e, "url": u or ""}
            for t, e, u in rows.all()
        ]
        return out, n


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
