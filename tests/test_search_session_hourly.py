"""Search session, hourly slots (актуальный слот без догона), лимит до часа."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from src import database as db
from src.hh.apply_types import ApplyOutcome, ApplyStatus
from src.models import VacancySeen, VacancyStatus
from src.pipeline import run_user_pipeline
from tests.conftest import make_vacancy


@pytest.fixture
async def sqlite_db(tmp_path):
    path = tmp_path / "sess.sqlite"
    db.init_db(f"sqlite+aiosqlite:///{path}")
    await db.create_tables()
    yield
    db._engine = None  # type: ignore[attr-defined]
    db._session_factory = None  # type: ignore[attr-defined]


CID = 7001
T0 = datetime(2025, 6, 1, 10, 0, 0, tzinfo=timezone.utc)


@pytest.mark.asyncio
async def test_start_search_session_sets_started_at_and_resets_slot(sqlite_db) -> None:
    await db.save_user_settings(CID, hh_email="a@b.c")
    await db.start_search_session(CID, T0)
    s = await db.get_user_settings(CID)
    assert s is not None
    assert s.search_session_started_at is not None
    sat = s.search_session_started_at
    if sat.tzinfo is None:
        sat = sat.replace(tzinfo=timezone.utc)
    assert (sat - T0).total_seconds() < 1
    assert s.last_hourly_report_slot is None


@pytest.mark.asyncio
async def test_hourly_current_slot_not_before_one_hour(sqlite_db) -> None:
    await db.save_user_settings(CID, hh_email="x@y.z")
    await db.start_search_session(CID, T0)
    slot, prev = await db.try_claim_current_hourly_report_slot(CID, T0 + timedelta(minutes=59))
    assert slot is None


@pytest.mark.asyncio
async def test_hourly_claims_only_current_slot_at_three_hours(sqlite_db) -> None:
    await db.save_user_settings(CID, hh_email="x@y.z")
    await db.start_search_session(CID, T0)
    slot, prev = await db.try_claim_current_hourly_report_slot(CID, T0 + timedelta(hours=3, minutes=1))
    assert slot == 3
    assert prev == 0
    us = await db.get_user_settings(CID)
    assert us.last_hourly_report_slot == 3
    slot2, _ = await db.try_claim_current_hourly_report_slot(CID, T0 + timedelta(hours=3, minutes=30))
    assert slot2 is None


@pytest.mark.asyncio
async def test_hourly_slot_sequential_still_one_per_hour(sqlite_db) -> None:
    await db.save_user_settings(CID, hh_email="x@y.z")
    await db.start_search_session(CID, T0)
    s1, p1 = await db.try_claim_current_hourly_report_slot(CID, T0 + timedelta(hours=1))
    assert s1 == 1 and p1 == 0
    s2, p2 = await db.try_claim_current_hourly_report_slot(CID, T0 + timedelta(hours=2))
    assert s2 == 2 and p2 == 1


@pytest.mark.asyncio
async def test_revert_hourly_after_failed_slot_three(sqlite_db) -> None:
    await db.save_user_settings(CID, hh_email="x@y.z")
    await db.start_search_session(CID, T0)
    slot, prev = await db.try_claim_current_hourly_report_slot(CID, T0 + timedelta(hours=3))
    assert slot == 3
    await db.revert_hourly_report_to_previous_last(CID, prev)
    us = await db.get_user_settings(CID)
    assert us.last_hourly_report_slot is None
    slot2, _ = await db.try_claim_current_hourly_report_slot(CID, T0 + timedelta(hours=3, minutes=5))
    assert slot2 == 3


@pytest.mark.asyncio
async def test_session_window_stats_only_after_start(sqlite_db) -> None:
    await db.save_user_settings(CID, hh_email="x@y.z")
    assert await db.get_session_window_stats(CID) is None
    await db.start_search_session(CID, T0)
    async with db.get_session() as session:
        session.add(
            VacancySeen(
                chat_id=CID,
                vacancy_id="v1",
                title="T",
                employer="E",
                url="https://hh.ru/1",
                status=VacancyStatus.REQUIRES_TEST,
                seen_at=T0 + timedelta(minutes=30),
            )
        )
    st = await db.get_session_window_stats(CID)
    assert st is not None
    assert st["requires_test"] == 1


@pytest.mark.asyncio
async def test_get_session_test_vacancies_with_url(sqlite_db) -> None:
    await db.save_user_settings(CID, hh_email="x@y.z")
    await db.start_search_session(CID, T0)
    async with db.get_session() as session:
        session.add(
            VacancySeen(
                chat_id=CID,
                vacancy_id="v1",
                title="Java Test",
                employer="ACME",
                url="https://hh.ru/vacancy/1",
                status=VacancyStatus.REQUIRES_TEST,
                seen_at=T0 + timedelta(minutes=5),
            )
        )
    rows, total = await db.get_session_test_vacancies(CID, limit=10)
    assert total == 1
    assert rows[0]["title"] == "Java Test"
    assert "hh.ru" in rows[0]["url"]


def _mock_cfg(monkeypatch, *, daily_limit: int = 1) -> None:
    cfg = SimpleNamespace()
    cfg.hh = SimpleNamespace(
        user_agent="UA",
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
            search_poll_interval_max_seconds=300.0,
            same_result_backoff_enabled=True,
        ),
    )
    cfg.storage = SimpleNamespace(retention_days=30)
    monkeypatch.setattr("src.pipeline.get_config", lambda: cfg)


@pytest.mark.asyncio
async def test_limit_reached_before_one_hour_sends_final_not_hourly_clears_session(
    sqlite_db, monkeypatch
) -> None:
    """Лимит до 1 ч: финальный отчёт, не почасовой, session очищена, слот hourly не занят."""
    LIM = 8002
    await db.save_user_settings(
        LIM,
        hh_email="u@u.u",
        hhtoken="tok",
        resume_id="r1",
        keywords_json='["Java developer"]',
    )
    start = datetime.now(timezone.utc) - timedelta(minutes=30)
    await db.start_search_session(LIM, start)

    sent: list[tuple[str, str]] = []

    class BotStub:
        async def send_message(self, *args: object, **kwargs: object) -> None:
            text = kwargs.get("text") or (args[1] if len(args) > 1 else "")
            sent.append(("msg", str(text)))

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

        async def search_all(self, **_k):
            return [
                make_vacancy(
                    vacancy_id="lim1",
                    name="Java Developer",
                    requirement="Java 17",
                )
            ]

        async def apply(self, **_k):
            return ApplyOutcome(
                status=ApplyStatus.APPLIED,
                http_status=200,
                error_code=None,
                error_message=None,
                retryable=False,
            )

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)
    _mock_cfg(monkeypatch, daily_limit=1)

    r = await run_user_pipeline(
        chat_id=LIM,
        hh_email="u@u.u",
        hh_password=None,
        hhtoken="tok",
        resume_id="r1",
        keywords=["Java developer"],
        cover_letter="",
        cancel_event=__import__("asyncio").Event(),
        bot=BotStub(),
    )
    assert r["stopped_by_limit"] is True
    assert any("Финальный" in t for _, t in sent), sent
    assert not any("Почасовой отчёт" in t for _, t in sent), sent

    us = await db.get_user_settings(LIM)
    assert us.search_session_started_at is None
    assert us.last_hourly_report_slot is None


@pytest.mark.asyncio
async def test_back_to_menu_clears_search_session(sqlite_db, monkeypatch) -> None:
    from src.bot import handlers as h

    BID = 8003
    await db.save_user_settings(
        BID,
        hh_email="a@a.a",
        hhtoken="t",
        resume_id="r",
        keywords_json='["x"]',
    )
    await db.start_search_session(BID, datetime.now(timezone.utc))

    u = MagicMock()
    u.effective_chat.id = BID
    u.callback_query.answer = AsyncMock()
    u.callback_query.edit_message_text = AsyncMock()
    ctx = MagicMock()
    ctx.user_data = {"cancel_event": __import__("asyncio").Event()}
    monkeypatch.setattr(h, "_background_search_shutdown", AsyncMock())

    await h.back_to_menu(u, ctx)

    row = await db.get_user_settings(BID)
    assert row.search_session_started_at is None


@pytest.mark.asyncio
async def test_search_task_finish_clears_session(sqlite_db, monkeypatch) -> None:
    from src.bot import handlers as h

    TID = 8004
    await db.save_user_settings(
        TID,
        hh_email="b@b.b",
        hhtoken="t",
        resume_id="r",
        keywords_json='["z"]',
    )
    await db.start_search_session(TID, datetime.now(timezone.utc))

    cfg = SimpleNamespace()
    cfg.hh = SimpleNamespace(search=SimpleNamespace(daily_apply_limit=50))
    monkeypatch.setattr(h, "get_config", lambda: cfg)

    async def fake_pipeline(**_kw):
        return {
            "applied": 0,
            "stopped_by_limit": False,
            "hh_temp_unavailable": False,
            "session_invalid": False,
        }

    monkeypatch.setattr("src.pipeline.run_user_pipeline", fake_pipeline)

    class BotStub:
        async def edit_message_text(self, **_k) -> None:
            pass

        async def send_message(self, **_k) -> None:
            pass

    await h._search_task(
        chat_id=TID,
        bot=BotStub(),
        cancel_event=__import__("asyncio").Event(),
        status_msg_id=1,
    )
    assert (await db.get_user_settings(TID)).search_session_started_at is None


@pytest.mark.asyncio
async def test_forced_cancel_path_clears_session(sqlite_db, monkeypatch) -> None:
    """После forced cancel в _send_post_stop_summary сессия очищается."""
    import asyncio

    FID = 8005
    await db.save_user_settings(
        FID, hh_email="c@c.c", hhtoken="t", resume_id="r", keywords_json='["k"]'
    )
    await db.start_search_session(FID, datetime.now(timezone.utc))

    from src.bot import handlers as h

    async def slow_task() -> None:
        await asyncio.sleep(3600)

    t = asyncio.create_task(slow_task())
    _n = [0]
    _orig = asyncio.wait_for

    async def _wf(aw, timeout=None):
        _n[0] += 1
        if _n[0] == 1:
            raise asyncio.TimeoutError()
        return await _orig(aw, timeout=timeout)

    monkeypatch.setattr(asyncio, "wait_for", _wf)

    class BotStub:
        async def send_message(self, **_k) -> None:
            pass

    await h._send_post_stop_summary(FID, t, BotStub(), 100)
    t.cancel()
    try:
        await t
    except asyncio.CancelledError:
        pass
    row = await db.get_user_settings(FID)
    assert row.search_session_started_at is None
