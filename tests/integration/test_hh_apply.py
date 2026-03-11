"""
Integration tests for hh.ru apply flow.

Требования:
  HH_TOKEN      — hhtoken cookie (SELECT hhtoken FROM user_settings LIMIT 1;)
  HH_RESUME_ID  — resume_id без query string (SELECT resume_id FROM user_settings LIMIT 1;)
  HH_VACANCY_ID — ID вакансии, на которую ещё не откликались

Запуск:
  HH_TOKEN='...' HH_RESUME_ID=... HH_VACANCY_ID=... pytest tests/integration/ -v -s -m integration

Тесты НЕ запускаются в обычном CI, только при наличии переменных окружения.
"""
from __future__ import annotations

import os

import pytest

from src.hh.client import HHClient

HH_TOKEN = os.getenv("HH_TOKEN", "")
HH_RESUME_ID = os.getenv("HH_RESUME_ID", "")
HH_VACANCY_ID = os.getenv("HH_VACANCY_ID", "")

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/122.0.0.0 Safari/537.36"
)

pytestmark = pytest.mark.integration


def _require_env() -> None:
    missing = [
        k for k, v in [
            ("HH_TOKEN", HH_TOKEN),
            ("HH_RESUME_ID", HH_RESUME_ID),
            ("HH_VACANCY_ID", HH_VACANCY_ID),
        ]
        if not v
    ]
    if missing:
        pytest.skip(f"Missing env vars: {', '.join(missing)}")


@pytest.fixture()
async def hh_client():
    """HHClient с активной сессией через hhtoken."""
    async with HHClient(user_agent=USER_AGENT, hhtoken=HH_TOKEN) as client:
        yield client


@pytest.mark.asyncio
async def test_get_resumes(hh_client: HHClient) -> None:
    """Проверяем, что hhtoken работает и мы можем получить список резюме."""
    _require_env()
    resumes = await hh_client.get_resumes()
    assert resumes, "Список резюме пуст — возможно, hhtoken истёк"
    print(f"\nНайдено резюме: {len(resumes)}")
    for r in resumes:
        assert r["id"], "resume id не должен быть пустым"
        assert "?" not in r["id"], f"resume id содержит query string: {r['id']!r}"
        assert r["title"], "resume title не должен быть пустым"
        print(f"  id={r['id']}  title={r['title']!r}")


@pytest.mark.asyncio
async def test_resume_id_has_no_query_string(hh_client: HHClient) -> None:
    """Убеждаемся, что парсер резюме не включает query string в ID."""
    _require_env()
    resumes = await hh_client.get_resumes()
    if not resumes:
        pytest.skip("Нет резюме на аккаунте")
    for r in resumes:
        assert "?" not in r["id"], f"resume_id содержит query string: {r['id']!r}"
        assert "hhtmFrom" not in r["id"], f"resume_id содержит hhtmFrom: {r['id']!r}"


@pytest.mark.asyncio
async def test_apply_without_letter(hh_client: HHClient) -> None:
    """Откликаемся через /applicant/vacancy_response/popup БЕЗ письма."""
    _require_env()
    ok = await hh_client.apply(
        vacancy_id=HH_VACANCY_ID,
        resume_id=HH_RESUME_ID,
        letter="",
    )
    print(f"\napply without letter: ok={ok}")
    assert ok, (
        f"Отклик не прошёл (vacancy={HH_VACANCY_ID!r}, resume={HH_RESUME_ID!r}). "
        "Проверьте логи hh.apply.failed/hh.apply.result."
    )


@pytest.mark.asyncio
async def test_apply_with_cover_letter(hh_client: HHClient) -> None:
    """Откликаемся через /applicant/vacancy_response/popup С письмом."""
    _require_env()
    letter = (
        "Добрый день!\n\n"
        "Меня заинтересовала эта вакансия. "
        "Я опытный разработчик и готов обсудить сотрудничество.\n\n"
        "С уважением."
    )
    ok = await hh_client.apply(
        vacancy_id=HH_VACANCY_ID,
        resume_id=HH_RESUME_ID,
        letter=letter,
    )
    print(f"\napply with cover letter: ok={ok}")
    assert ok, "Отклик с письмом не прошёл. Смотрите логи hh.apply.failed/hh.apply.result."
