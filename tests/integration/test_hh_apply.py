"""
Integration tests for hh.ru apply flow.

Требования:
  HH_TOKEN      — hhtoken cookie из user_settings (SELECT hhtoken FROM user_settings LIMIT 1;)
  HH_RESUME_ID  — чистый resume_id (SELECT resume_id FROM user_settings LIMIT 1;)
  HH_VACANCY_ID — ID вакансии для тестового отклика (возьмите любую из vacancies_seen)

Запуск:
  HH_TOKEN=... HH_RESUME_ID=... HH_VACANCY_ID=... pytest tests/integration/ -v -m integration

Тесты НЕ запускаются в обычном CI, только при наличии переменных окружения.
"""
from __future__ import annotations

import os

import pytest

from src.hh.client import HHClient

HH_TOKEN = os.getenv("HH_TOKEN", "")
HH_RESUME_ID = os.getenv("HH_RESUME_ID", "")
HH_VACANCY_ID = os.getenv("HH_VACANCY_ID", "")

USER_AGENT = "HHVacancyAssistant/1.0 (integration-test)"

pytestmark = pytest.mark.integration


def _require_env() -> None:
    missing = [k for k, v in [("HH_TOKEN", HH_TOKEN), ("HH_RESUME_ID", HH_RESUME_ID), ("HH_VACANCY_ID", HH_VACANCY_ID)] if not v]
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
    for r in resumes:
        assert r["id"], "resume id не должен быть пустым"
        assert "?" not in r["id"], f"resume id содержит query string: {r['id']!r}"
        assert r["title"], "resume title не должен быть пустым"
    print(f"\nНайдено резюме: {len(resumes)}")
    for r in resumes:
        print(f"  id={r['id']}  title={r['title']!r}")


@pytest.mark.asyncio
async def test_apply_without_letter(hh_client: HHClient) -> None:
    """Откликаемся на вакансию БЕЗ сопроводительного письма."""
    _require_env()
    ok = await hh_client.apply(
        vacancy_id=HH_VACANCY_ID,
        resume_id=HH_RESUME_ID,
        letter="",
    )
    # hh.ru возвращает 200 при успехе или при повторном отклике (уже откликнулся)
    # 406 означает, что отклик отклонён по другой причине (не подходит резюме и т.п.)
    print(f"\napply without letter: ok={ok}")
    assert ok, (
        f"Отклик не прошёл. Проверьте HH_VACANCY_ID={HH_VACANCY_ID!r} и HH_RESUME_ID={HH_RESUME_ID!r}. "
        "Смотрите тело ответа в логе hh.apply.failed."
    )


@pytest.mark.asyncio
async def test_apply_with_cover_letter(hh_client: HHClient) -> None:
    """Откликаемся на вакансию С сопроводительным письмом."""
    _require_env()
    cover_letter = (
        "Добрый день!\n\n"
        "Меня заинтересовала вакансия «{title}» в компании {employer}.\n"
        "Я опытный разработчик и готов обсудить сотрудничество.\n\n"
        "С уважением, тестовый отклик."
    )
    # Подставляем заглушки, так как у нас нет данных вакансии здесь
    letter = cover_letter.format(title="тестовая вакансия", employer="тестовая компания")

    ok = await hh_client.apply(
        vacancy_id=HH_VACANCY_ID,
        resume_id=HH_RESUME_ID,
        letter=letter,
    )
    print(f"\napply with cover letter: ok={ok}")
    assert ok, (
        f"Отклик с письмом не прошёл. Смотрите тело ответа в логе hh.apply.failed."
    )


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
