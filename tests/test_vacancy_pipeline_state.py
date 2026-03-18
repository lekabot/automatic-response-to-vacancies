"""Состояние вакансий в БД (per chat_id), пайплайн, lifecycle search_task."""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from src import database as db
from src.database import ClaimReason
from src.hh.apply_types import ApplyOutcome, ApplyStatus
from src.models import VacancySeen, VacancyStatus
from src.pipeline import render_cover_letter, run_user_pipeline, validate_cover_letter_braces
from tests.conftest import make_vacancy

C1 = 1001
C2 = 1002


@pytest.fixture
async def sqlite_db(tmp_path):
    path = tmp_path / "test.sqlite"
    db.init_db(f"sqlite+aiosqlite:///{path}")
    await db.create_tables()
    yield
    db._engine = None  # type: ignore[attr-defined]
    db._session_factory = None  # type: ignore[attr-defined]


def _pk(cid: int, vid: str) -> dict:
    return {"chat_id": cid, "vacancy_id": vid}


def _mock_pipeline_config(monkeypatch, *, daily_limit: int = 500) -> None:
    cfg = SimpleNamespace()
    cfg.hh = SimpleNamespace(
        user_agent="TestUA/1",
        rate_limit=SimpleNamespace(qps=50.0, burst=50),
        search=SimpleNamespace(
            area=[1],
            schedule=None,
            employment=None,
            search_field=None,
            published_within_hours=24,
            max_vacancies_per_run=50,
            daily_apply_limit=daily_limit,
            exclude_keywords=[],
            vacancy_lease_minutes=10,
            pipeline_heartbeat_every=100,
            apply_total_timeout_seconds=30.0,
            apply_per_attempt_timeout_seconds=10.0,
            repeat_interval_minutes=60,
            search_poll_interval_seconds=0,
        ),
    )
    cfg.storage = SimpleNamespace(retention_days=30)
    monkeypatch.setattr("src.pipeline.get_config", lambda: cfg)


@pytest.mark.asyncio
async def test_claim_new_vacancy(sqlite_db) -> None:
    d = await db.try_claim_vacancy_for_processing(
        chat_id=C1,
        vacancy_id="v1",
        title="T",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    assert d.reason == ClaimReason.CLAIMED
    assert d.attempt_count == 1
    assert d.current_status == VacancyStatus.IN_PROGRESS
    assert d.next_retry_at is None


@pytest.mark.asyncio
async def test_two_users_same_vacancy_id_independent(sqlite_db) -> None:
    await db.try_claim_vacancy_for_processing(
        chat_id=C1,
        vacancy_id="same",
        title="A",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    d2 = await db.try_claim_vacancy_for_processing(
        chat_id=C2,
        vacancy_id="same",
        title="B",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    assert d2.reason == ClaimReason.CLAIMED
    assert d2.attempt_count == 1
    async with db.get_session() as session:
        r1 = await session.get(VacancySeen, _pk(C1, "same"))
        r2b = await session.get(VacancySeen, _pk(C2, "same"))
    assert r1 and r2b
    assert r1.title == "A"
    assert r2b.title == "B"


@pytest.mark.asyncio
async def test_claim_applied_terminal_skip(sqlite_db) -> None:
    await db.upsert_vacancy(
        chat_id=C1,
        vacancy_id="v1",
        title="T",
        employer="E",
        url="u",
        salary_text=None,
        status=VacancyStatus.APPLIED,
    )
    d = await db.try_claim_vacancy_for_processing(
        chat_id=C1,
        vacancy_id="v1",
        title="T",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    assert d.reason == ClaimReason.SKIP_TERMINAL
    assert d.current_status == VacancyStatus.APPLIED


@pytest.mark.asyncio
async def test_claim_backoff_active(sqlite_db) -> None:
    future = datetime.now(timezone.utc) + timedelta(hours=1)
    async with db.get_session() as session:
        session.add(
            VacancySeen(
                chat_id=C1,
                vacancy_id="v1",
                title="T",
                employer="E",
                url="u",
                status=VacancyStatus.APPLY_TIMEOUT,
                attempt_count=2,
                next_retry_at=future,
            )
        )
    d = await db.try_claim_vacancy_for_processing(
        chat_id=C1,
        vacancy_id="v1",
        title="T",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    assert d.reason == ClaimReason.SKIP_BACKOFF
    assert d.current_status == VacancyStatus.APPLY_TIMEOUT
    assert d.next_retry_at is not None
    assert d.next_retry_at == future


@pytest.mark.asyncio
async def test_claim_in_progress_within_lease_skip(sqlite_db) -> None:
    started = datetime.now(timezone.utc) - timedelta(minutes=2)
    async with db.get_session() as session:
        session.add(
            VacancySeen(
                chat_id=C1,
                vacancy_id="vip",
                title="T",
                employer="E",
                url="u",
                status=VacancyStatus.IN_PROGRESS,
                attempt_count=1,
                processing_started_at=started,
            )
        )
    d = await db.try_claim_vacancy_for_processing(
        chat_id=C1,
        vacancy_id="vip",
        title="T",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    assert d.reason == ClaimReason.SKIP_IN_PROGRESS
    assert d.current_status == VacancyStatus.IN_PROGRESS
    assert d.next_retry_at is not None
    assert d.next_retry_at == started.replace(tzinfo=timezone.utc) + timedelta(minutes=10)


@pytest.mark.asyncio
async def test_claim_stale_in_progress_reclaimed(sqlite_db) -> None:
    old = datetime.now(timezone.utc) - timedelta(minutes=30)
    async with db.get_session() as session:
        session.add(
            VacancySeen(
                chat_id=C1,
                vacancy_id="v1",
                title="T",
                employer="E",
                url="u",
                status=VacancyStatus.IN_PROGRESS,
                attempt_count=1,
                processing_started_at=old,
            )
        )
    d = await db.try_claim_vacancy_for_processing(
        chat_id=C1,
        vacancy_id="v1",
        title="T",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    assert d.reason == ClaimReason.CLAIMED
    assert d.attempt_count == 2


@pytest.mark.asyncio
async def test_get_applied_today_count_per_chat(sqlite_db) -> None:
    now = datetime.now(timezone.utc)
    async with db.get_session() as session:
        session.add(
            VacancySeen(
                chat_id=C1,
                vacancy_id="x",
                title="T",
                employer="E",
                url="u",
                status=VacancyStatus.APPLIED,
                seen_at=now,
            )
        )
        session.add(
            VacancySeen(
                chat_id=C2,
                vacancy_id="y",
                title="T",
                employer="E",
                url="u",
                status=VacancyStatus.APPLIED,
                seen_at=now,
            )
        )
    assert await db.get_applied_today_count(C1) == 1
    assert await db.get_applied_today_count(C2) == 1


@pytest.mark.asyncio
async def test_get_today_stats_scoped(sqlite_db) -> None:
    now = datetime.now(timezone.utc)
    async with db.get_session() as session:
        session.add(
            VacancySeen(
                chat_id=C1,
                vacancy_id="a",
                title="T1",
                employer="E",
                url="u1",
                status=VacancyStatus.APPLIED,
                seen_at=now,
            )
        )
        session.add(
            VacancySeen(
                chat_id=C2,
                vacancy_id="b",
                title="T2",
                employer="E",
                url="u2",
                status=VacancyStatus.SKIPPED,
                seen_at=now,
            )
        )
    s1 = await db.get_today_stats(C1)
    s2 = await db.get_today_stats(C2)
    assert s1["applied"] == 1
    assert s2["applied"] == 0
    assert s2["skipped"] == 1


@pytest.mark.asyncio
async def test_reset_applied_only_one_chat(sqlite_db) -> None:
    await db.upsert_vacancy(
        chat_id=C1, vacancy_id="a", title="T", employer="E", url="u", salary_text=None, status=VacancyStatus.APPLIED
    )
    await db.upsert_vacancy(
        chat_id=C2, vacancy_id="b", title="T", employer="E", url="u", salary_text=None, status=VacancyStatus.APPLIED
    )
    n = await db.reset_applied_vacancies(C1)
    assert n == 1
    async with db.get_session() as session:
        assert await session.get(VacancySeen, _pk(C1, "a")) is None
        assert await session.get(VacancySeen, _pk(C2, "b")) is not None


@pytest.mark.asyncio
async def test_pipeline_timeout_persists_apply_timeout(sqlite_db, monkeypatch) -> None:
    _mock_pipeline_config(monkeypatch)
    v = make_vacancy(vacancy_id="vx")

    class MockHH:
        def __init__(self, *_, **__) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.VALID

        async def login(self, *_a, **_k):
            return True

        async def search_all(self, **_k):
            return [v]

        async def apply(self, **_k):
            return ApplyOutcome(
                status=ApplyStatus.TIMEOUT,
                http_status=None,
                error_code="t",
                error_message="m",
                retryable=True,
            )

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)

    await run_user_pipeline(
        chat_id=C1,
        hh_email="a@b.c",
        hh_password=None,
        hhtoken="tok",
        resume_id="rid",
        keywords=["python"],
        cover_letter="",
        cancel_event=asyncio.Event(),
    )
    async with db.get_session() as session:
        row = await session.get(VacancySeen, _pk(C1, "vx"))
    assert row is not None
    assert row.status == VacancyStatus.APPLY_TIMEOUT


@pytest.mark.asyncio
async def test_pipeline_session_invalid_exits_early(sqlite_db, monkeypatch) -> None:
    _mock_pipeline_config(monkeypatch)

    class MockHH:
        def __init__(self, *_, **__) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.INVALID

        async def search_all(self, **_k):
            raise AssertionError("search must not run")

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)
    r = await run_user_pipeline(
        chat_id=C1,
        hh_email="a@b.c",
        hh_password=None,
        hhtoken="dead",
        resume_id="r",
        keywords=["Python"],
        cover_letter="",
        cancel_event=asyncio.Event(),
    )
    assert r["applied"] == 0
    assert r["session_invalid"] is True


@pytest.mark.asyncio
async def test_pipeline_hh_temp_unavailable_no_session_invalid(sqlite_db, monkeypatch) -> None:
    _mock_pipeline_config(monkeypatch)

    class MockHH:
        def __init__(self, *_, **__) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.TEMP_UNAVAILABLE

        async def search_all(self, **_k):
            raise AssertionError("no search on temp hh")

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)
    r = await run_user_pipeline(
        chat_id=C1,
        hh_email="a@b.c",
        hh_password=None,
        hhtoken="t",
        resume_id="r",
        keywords=["Python"],
        cover_letter="",
        cancel_event=asyncio.Event(),
    )
    assert r["hh_temp_unavailable"] is True
    assert r["session_invalid"] is False
    assert r["applied"] == 0


@pytest.mark.asyncio
async def test_pipeline_temp_error_and_applied(sqlite_db, monkeypatch) -> None:
    _mock_pipeline_config(monkeypatch)

    class MockHH:
        def __init__(self, outcomes: list[ApplyOutcome], *_, **__):
            self._outcomes = list(outcomes)
            self._i = 0

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.VALID

        async def login(self, *_a, **_k):
            return True

        async def search_all(self, **_k):
            return [
                make_vacancy(vacancy_id="a"),
                make_vacancy(vacancy_id="b"),
            ]

        async def apply(self, *, vacancy_id: str, **_k):
            o = self._outcomes[self._i]
            self._i += 1
            return o

    for status, vac_status in [
        (ApplyStatus.TEMP_ERROR, VacancyStatus.APPLY_TEMP_ERROR),
        (ApplyStatus.ALREADY_APPLIED, VacancyStatus.ALREADY_APPLIED),
        (ApplyStatus.APPLIED, VacancyStatus.APPLIED),
    ]:
        await db.reset_applied_vacancies(C1)
        out = ApplyOutcome(
            status=status,
            http_status=503 if status == ApplyStatus.TEMP_ERROR else 200,
            error_code="x",
            error_message=None,
            retryable=status == ApplyStatus.TEMP_ERROR,
        )
        monkeypatch.setattr(
            "src.pipeline.HHClient",
            lambda *a, **k: MockHH([out, ApplyOutcome.applied()]),
        )
        await run_user_pipeline(
            chat_id=C1,
            hh_email="a@b.c",
            hh_password=None,
            hhtoken="t",
            resume_id="r",
            keywords=["Python"],
            cover_letter="",
            cancel_event=asyncio.Event(),
        )
        async with db.get_session() as session:
            row = await session.get(VacancySeen, _pk(C1, "a"))
        assert row.status == vac_status, status


def test_render_cover_letter_unknown_braces_preserved() -> None:
    t = "Опыт с {Kafka} и {Spring}, вакансия {title} в {employer}"
    r = render_cover_letter(t, title="Dev", employer="ACME")
    assert "{Kafka}" in r
    assert "{Spring}" in r
    assert "Dev" in r
    assert "ACME" in r


def test_validate_cover_letter_braces() -> None:
    assert validate_cover_letter_braces("ok {title}")[0]
    assert not validate_cover_letter_braces("bad {")[0]


@pytest.mark.asyncio
async def test_search_task_done_callback_clears_user_data() -> None:
    from src.bot.handlers import _search_task_done_callback

    ud: dict = {}
    done = asyncio.Event()

    async def slow():
        await done.wait()

    t = asyncio.create_task(slow())
    ud["search_task"] = t
    ud["cancel_event"] = asyncio.Event()
    t.add_done_callback(_search_task_done_callback(42, ud))
    done.set()
    await asyncio.wait_for(t, timeout=2.0)
    assert "search_task" not in ud


@pytest.mark.asyncio
async def test_get_applied_today_count_uses_sql_count(sqlite_db) -> None:
    now = datetime.now(timezone.utc)
    for i in range(7):
        async with db.get_session() as session:
            session.add(
                VacancySeen(
                    chat_id=C1,
                    vacancy_id=f"v{i}",
                    title="T",
                    employer="E",
                    url="u",
                    status=VacancyStatus.APPLIED,
                    seen_at=now,
                )
            )
    assert await db.get_applied_today_count(C1) == 7


@pytest.mark.asyncio
async def test_persist_terminal_truncates_last_error(sqlite_db) -> None:
    await db.try_claim_vacancy_for_processing(
        chat_id=C1,
        vacancy_id="z",
        title="T",
        employer="E",
        url="u",
        salary_text=None,
        retention_days=30,
        lease_minutes=10,
    )
    long_err = "x" * 5000
    await db.persist_terminal_vacancy(
        chat_id=C1,
        vacancy_id="z",
        status=VacancyStatus.APPLY_PERM_ERROR,
        last_error=long_err,
    )
    async with db.get_session() as session:
        row = await session.get(VacancySeen, _pk(C1, "z"))
    assert row and row.last_error is not None
    assert len(row.last_error) <= 1003
