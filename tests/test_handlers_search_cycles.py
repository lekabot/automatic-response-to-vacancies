"""Перед каждым циклом _search_task подтягивает UserSettings из БД."""
from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from src.bot import handlers as h


@pytest.mark.asyncio
async def test_search_task_second_cycle_uses_updated_keywords(monkeypatch) -> None:
    log_kw: list[list[str]] = []

    async def fake_run_user_pipeline(**kwargs):
        log_kw.append(list(kwargs["keywords"]))
        return {
            "applied": 0,
            "stopped_by_limit": False,
            "hh_temp_unavailable": False,
            "session_invalid": False,
        }

    monkeypatch.setattr("src.pipeline.run_user_pipeline", fake_run_user_pipeline)

    call_n = [0]

    def make_settings(words: list[str]) -> SimpleNamespace:
        s = SimpleNamespace()
        s.keywords = words
        s.hh_email = "u@e.com"
        s.hhtoken = "ht"
        s.resume_id = "res"
        s.hh_password = None
        s.cover_letter = ""
        s.is_complete = lambda: True
        return s

    async def fake_get_settings(_cid: int):
        call_n[0] += 1
        if call_n[0] == 1:
            return make_settings(["cycle_a"])
        return make_settings(["cycle_b"])

    monkeypatch.setattr(h.db, "get_user_settings", fake_get_settings)

    async def fake_stats(_cid: int):
        return {
            "applied": 0,
            "failed": 0,
            "retry_later": 0,
            "skipped": 0,
            "requires_test": 0,
            "already_applied": 0,
            "failed_vacancies": [],
            "counts": {},
        }

    monkeypatch.setattr(h.db, "get_today_stats", fake_stats)

    cfg = SimpleNamespace()
    cfg.hh = SimpleNamespace(
        search=SimpleNamespace(
            repeat_interval_minutes=1.0 / 60.0,
            daily_apply_limit=100,
        )
    )
    monkeypatch.setattr(h, "get_config", lambda: cfg)

    class BotStub:
        async def send_message(self, *_a, **_k) -> None:
            return None

        async def edit_message_text(self, *_a, **_k) -> None:
            return None

    ev = asyncio.Event()

    async def stop_soon() -> None:
        await asyncio.sleep(2.4)
        ev.set()

    asyncio.create_task(stop_soon())
    await h._search_task(
        chat_id=4242,
        bot=BotStub(),
        cancel_event=ev,
        status_msg_id=1,
    )
    assert len(log_kw) >= 2
    assert log_kw[0] == ["cycle_a"]
    assert log_kw[1] == ["cycle_b"]
