"""Непрерывный поиск: короткий poll, несколько волн до лимита."""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from src import database as db
from src.hh.apply_types import ApplyOutcome, ApplyStatus
from src.pipeline import run_user_pipeline
from tests.conftest import make_vacancy


@pytest.fixture
async def sqlite_db(tmp_path):
    path = tmp_path / "cont.sqlite"
    db.init_db(f"sqlite+aiosqlite:///{path}")
    await db.create_tables()
    yield
    db._engine = None  # type: ignore[attr-defined]
    db._session_factory = None  # type: ignore[attr-defined]


def _cfg(monkeypatch, *, daily_limit: int = 5, poll_sec: float = 0.02) -> None:
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
            search_poll_interval_seconds=poll_sec,
        ),
    )
    cfg.storage = SimpleNamespace(retention_days=30)
    monkeypatch.setattr("src.pipeline.get_config", lambda: cfg)


@pytest.mark.asyncio
async def test_three_waves_apply_until_limit_no_hour_sleep(sqlite_db, monkeypatch) -> None:
    """После волны с откликом — короткий poll, следующая волна; до лимита не спим час."""
    CID = 92001
    await db.save_user_settings(
        CID,
        hh_email="u@u.u",
        hhtoken="t",
        resume_id="r",
        keywords_json='["Java"]',
    )
    _cfg(monkeypatch, daily_limit=3, poll_sec=0.02)

    class MockHH:
        def __init__(self, *_, **__) -> None:
            self._n = 0

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.VALID

        async def search_all(self, **_k):
            self._n += 1
            if self._n > 3:
                return []
            return [
                make_vacancy(
                    vacancy_id=f"jv{self._n}",
                    name="Java Developer",
                    requirement="Java",
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

    events: list[tuple[str, dict]] = []

    def cap(msg: str, **kw: object) -> None:
        events.append((msg, dict(kw)))

    import src.pipeline as pl

    m = MagicMock()
    m.info = cap
    m.warning = MagicMock()
    m.error = MagicMock()
    m.exception = MagicMock()
    monkeypatch.setattr(pl, "log", m)

    r = await run_user_pipeline(
        chat_id=CID,
        hh_email="u@u.u",
        hh_password=None,
        hhtoken="t",
        resume_id="r",
        keywords=["Java"],
        cover_letter="",
        cancel_event=asyncio.Event(),
        bot=None,
    )
    assert r["stopped_by_limit"] is True
    waves = [e for e in events if e[0] == "pipeline.wave.start"]
    assert len(waves) == 3
    polls = [e for e in events if e[0] == "pipeline.poll_sleep"]
    assert len(polls) == 2
    assert all(p[1]["sleep_seconds"] <= 1.0 for p in polls)
    assert not any(e[0] == "pipeline.idle.before_next_wave" for e in events)
    assert any(e[0] == "pipeline.search.limit_reached" for e in events)


@pytest.mark.asyncio
async def test_empty_waves_short_poll_then_continue(sqlite_db, monkeypatch) -> None:
    """Нет вакансий — no_new_vacancies, короткий poll, снова поиск."""
    CID = 92002
    await db.save_user_settings(
        CID, hh_email="a@a.a", hhtoken="t", resume_id="r", keywords_json='["Python"]'
    )
    _cfg(monkeypatch, daily_limit=50, poll_sec=0.03)

    class MockHH:
        def __init__(self, *_, **__) -> None:
            self.w = 0

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.VALID

        async def search_all(self, **_k):
            self.w += 1
            if self.w <= 2:
                return []
            return [
                make_vacancy(
                    vacancy_id="one",
                    name="Python",
                    requirement="Python",
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
    ev = asyncio.Event()

    async def stop() -> None:
        await asyncio.sleep(0.2)
        ev.set()

    events2: list[tuple[str, dict]] = []

    def cap2(msg: str, **kw: object) -> None:
        events2.append((msg, dict(kw)))

    import src.pipeline as pl2

    m2 = MagicMock()
    m2.info = cap2
    m2.warning = MagicMock()
    m2.error = MagicMock()
    m2.exception = MagicMock()
    monkeypatch.setattr(pl2, "log", m2)

    asyncio.create_task(stop())
    await run_user_pipeline(
        chat_id=CID,
        hh_email="a@a.a",
        hh_password=None,
        hhtoken="t",
        resume_id="r",
        keywords=["Python"],
        cover_letter="",
        cancel_event=ev,
        bot=None,
    )
    no_new = [e for e in events2 if e[0] == "pipeline.search.no_new_vacancies"]
    assert len(no_new) >= 2
    polls = [e for e in events2 if e[0] == "pipeline.poll_sleep"]
    assert len(polls) >= 2
    assert all(p[1]["sleep_seconds"] == 0.03 for p in polls)
    assert polls[0][1]["reason"] == "no_new_vacancies"
    assert polls[1][1]["reason"] == "no_new_vacancies"
    assert any(p[1]["reason"] == "wave_finished_continue" for p in polls)


@pytest.mark.asyncio
async def test_hourly_report_once_while_polling_many_cycles(sqlite_db, monkeypatch) -> None:
    """Почасовой отчёт по слоту сессии (1 раз), цикл poll продолжается дальше."""
    CID = 92003
    now = datetime.now(timezone.utc)
    await db.save_user_settings(
        CID, hh_email="h@h.h", hhtoken="t", resume_id="r", keywords_json='["Python"]'
    )
    await db.start_search_session(CID, now - timedelta(minutes=65))

    sent: list[str] = []

    class BotStub:
        async def send_message(self, **_kw: object) -> None:
            sent.append(str(_kw.get("text", "")))

    _cfg(monkeypatch, daily_limit=50, poll_sec=0.04)

    class MockHH:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.VALID

        async def search_all(self, **_k):
            return []

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)
    ev = asyncio.Event()

    async def stop_later() -> None:
        await asyncio.sleep(0.45)
        ev.set()

    events3: list[tuple[str, dict]] = []

    def cap3(msg: str, **kw: object) -> None:
        events3.append((msg, dict(kw)))

    import src.pipeline as pl3

    m3 = MagicMock()
    m3.info = cap3
    m3.warning = MagicMock()
    m3.error = MagicMock()
    m3.exception = MagicMock()
    monkeypatch.setattr(pl3, "log", m3)

    asyncio.create_task(stop_later())
    await run_user_pipeline(
        chat_id=CID,
        hh_email="h@h.h",
        hh_password=None,
        hhtoken="t",
        resume_id="r",
        keywords=["Python"],
        cover_letter="",
        cancel_event=ev,
        bot=BotStub(),
    )
    hourly_msgs = [t for t in sent if "Почасовой отчёт" in t]
    assert len(hourly_msgs) == 1
    waves = [e for e in events3 if e[0] == "pipeline.wave.start"]
    assert len(waves) >= 4
</think>

I need to fix the test_empty_waves test - I didn't finish it. Let me use the same log capture pattern as the first test.
</think>


<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>
StrReplace