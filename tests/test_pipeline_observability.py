"""Наблюдаемость волны, idle, crash, done callback."""
from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from src import database as db
from src.models import VacancySeen, VacancyStatus
from src.pipeline import run_user_pipeline
from tests.conftest import make_vacancy

C = 91001


@pytest.fixture
async def sqlite_db(tmp_path):
    path = tmp_path / "obs.sqlite"
    db.init_db(f"sqlite+aiosqlite:///{path}")
    await db.create_tables()
    yield
    db._engine = None  # type: ignore[attr-defined]
    db._session_factory = None  # type: ignore[attr-defined]


def _cfg(monkeypatch, *, repeat_minutes: float = 0, daily_limit: int = 50) -> None:
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
            repeat_interval_minutes=repeat_minutes,
        ),
    )
    cfg.storage = SimpleNamespace(retention_days=30)
    monkeypatch.setattr("src.pipeline.get_config", lambda: cfg)


def _mock_pipeline_log(monkeypatch, *, info_fn) -> None:
    import src.pipeline as pl

    m = MagicMock()
    m.info = info_fn
    m.warning = MagicMock()
    m.error = MagicMock()
    m.exception = MagicMock()
    monkeypatch.setattr(pl, "log", m)


@pytest.mark.asyncio
async def test_wave_summary_when_all_terminal_already_applied(sqlite_db, monkeypatch) -> None:
    info_events: list[tuple[str, dict]] = []

    def capture_info(msg: str, **kw: object) -> None:
        info_events.append((msg, dict(kw)))

    _mock_pipeline_log(monkeypatch, info_fn=capture_info)

    await db.save_user_settings(
        C, hh_email="a@a.a", hhtoken="t", resume_id="r", keywords_json='["python"]'
    )
    for vid in ("va", "vb"):
        async with db.get_session() as session:
            session.add(
                VacancySeen(
                    chat_id=C,
                    vacancy_id=vid,
                    title="T",
                    employer="E",
                    url="u",
                    status=VacancyStatus.ALREADY_APPLIED,
                    attempt_count=0,
                )
            )

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
                    vacancy_id="va",
                    name="Python developer",
                    requirement="Python 3",
                ),
                make_vacancy(
                    vacancy_id="vb",
                    name="Senior Python",
                    requirement="Python, Django",
                ),
            ]

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)
    _cfg(monkeypatch, repeat_minutes=0)

    await run_user_pipeline(
        chat_id=C,
        hh_email="a@a.a",
        hh_password=None,
        hhtoken="tok",
        resume_id="r",
        keywords=["python"],
        cover_letter="",
        cancel_event=asyncio.Event(),
        bot=None,
    )

    summaries = [e for e in info_events if e[0] == "pipeline.wave.summary"]
    assert len(summaries) == 1, info_events
    payload = summaries[0][1]
    assert payload["collected_total"] == 2
    assert payload["skipped_terminal"] == 2
    assert payload["apply_attempted"] == 0
    assert payload["terminal_only_wave"] is True
    assert payload["next_action"] == "complete"

    no_act = [e for e in info_events if e[0] == "pipeline.wave.no_actionable_vacancies"]
    assert len(no_act) == 1
    assert no_act[0][1]["breakdown"].get("ALREADY_APPLIED") == 2

    assert any(e[0] == "pipeline.search.completed" for e in info_events)


@pytest.mark.asyncio
async def test_idle_before_next_wave_logged(sqlite_db, monkeypatch) -> None:
    info_events: list[str] = []

    def capture_info(msg: str, **kw: object) -> None:
        info_events.append(msg)

    _mock_pipeline_log(monkeypatch, info_fn=capture_info)

    await db.save_user_settings(
        C + 1, hh_email="b@b.b", hhtoken="t", resume_id="r", keywords_json='["x"]'
    )

    class MockHH:
        def __init__(self, *_, **__) -> None:
            self._wave = 0

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return None

        async def validate_session_status(self):
            from src.hh.session_status import SessionValidationStatus

            return SessionValidationStatus.VALID

        async def search_all(self, **_k):
            self._wave += 1
            return [] if self._wave == 1 else []

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)
    _cfg(monkeypatch, repeat_minutes=1.0 / 3600)

    ev = asyncio.Event()

    async def stop_fast() -> None:
        await asyncio.sleep(0.15)
        ev.set()

    asyncio.create_task(stop_fast())
    await run_user_pipeline(
        chat_id=C + 1,
        hh_email="b@b.b",
        hh_password=None,
        hhtoken="tok",
        resume_id="r",
        keywords=["x"],
        cover_letter="",
        cancel_event=ev,
        bot=None,
    )

    assert "pipeline.idle.before_next_wave" in info_events
    assert "pipeline.idle.wakeup" in info_events


@pytest.mark.asyncio
async def test_pipeline_search_crashed_after_wave_body(sqlite_db, monkeypatch) -> None:
    exc_calls: list[str] = []

    def capture_exc(msg: str, **kw: object) -> None:
        exc_calls.append(msg)

    m = MagicMock()
    m.info = MagicMock()
    m.warning = MagicMock()
    m.error = MagicMock()
    m.exception = capture_exc
    import src.pipeline as pl

    monkeypatch.setattr(pl, "log", m)

    async def boom(_wave: int, _cid: int) -> None:
        raise RuntimeError("after_wave")

    monkeypatch.setattr("src.pipeline._after_wave_test_hook", boom)

    await db.save_user_settings(
        C + 2, hh_email="c@c.c", hhtoken="t", resume_id="r", keywords_json='["z"]'
    )

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
            return []

    monkeypatch.setattr("src.pipeline.HHClient", MockHH)
    _cfg(monkeypatch, repeat_minutes=0)

    with pytest.raises(RuntimeError, match="after_wave"):
        await run_user_pipeline(
            chat_id=C + 2,
            hh_email="c@c.c",
            hh_password=None,
            hhtoken="tok",
            resume_id="r",
            keywords=["z"],
            cover_letter="",
            cancel_event=asyncio.Event(),
            bot=None,
        )

    assert "pipeline.search.crashed" in exc_calls


@pytest.mark.asyncio
async def test_search_task_done_callback_finished(monkeypatch) -> None:
    from src.bot import handlers as h

    finished: list[str] = []

    def capture_info(msg: str, **kw: object) -> None:
        if msg in ("search_task.finished", "search_task.cancelled", "search_task.crashed"):
            finished.append(msg)

    hm = MagicMock()
    hm.info = capture_info
    hm.exception = MagicMock()
    monkeypatch.setattr(h, "log", hm)

    ud: dict = {}

    async def quick() -> None:
        await asyncio.sleep(0)

    t = asyncio.create_task(quick())
    ud["search_task"] = t
    cb = h._search_task_done_callback(777, ud)
    t.add_done_callback(cb)
    await asyncio.wait_for(t, timeout=2.0)
    assert "search_task.finished" in finished
    assert "search_task" not in ud
