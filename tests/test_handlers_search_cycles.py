"""Один вызов run_user_pipeline на сессию поиска."""
from __future__ import annotations

import pytest

from src.bot import handlers as h


@pytest.mark.asyncio
async def test_search_task_calls_pipeline_once_with_bot(monkeypatch) -> None:
    calls: list[int] = []

    async def fake_run_user_pipeline(**kwargs):
        calls.append(1)
        assert kwargs.get("bot") is not None
        return {
            "applied": 0,
            "stopped_by_limit": False,
            "hh_temp_unavailable": False,
            "session_invalid": False,
        }

    monkeypatch.setattr("src.pipeline.run_user_pipeline", fake_run_user_pipeline)

    def make_settings():
        s = type("S", (), {})()
        s.keywords = ["a"]
        s.hh_email = "u@e.com"
        s.hhtoken = "ht"
        s.resume_id = "res"
        s.hh_password = None
        s.cover_letter = ""
        s.is_complete = lambda: True
        return s

    async def fake_get(_cid: int):
        return make_settings()

    monkeypatch.setattr(h.db, "get_user_settings", fake_get)

    cfg = type("C", (), {})()
    cfg.hh = type("H", (), {})()
    cfg.hh.search = type("S", (), {})()
    cfg.hh.search.daily_apply_limit = 100
    monkeypatch.setattr(h, "get_config", lambda: cfg)

    async def noop_clear(_cid: int, log_event: str | None = None) -> bool:
        return False

    monkeypatch.setattr(h.db, "clear_search_session", noop_clear)

    async def fake_stats(_cid: int) -> dict:
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

    class BotStub:
        async def send_message(self, *_a, **_k) -> None:
            return None

        async def edit_message_text(self, *_a, **_k) -> None:
            return None

    import asyncio

    await h._search_task(
        chat_id=4242,
        bot=BotStub(),
        cancel_event=asyncio.Event(),
        status_msg_id=1,
    )
    assert len(calls) == 1
