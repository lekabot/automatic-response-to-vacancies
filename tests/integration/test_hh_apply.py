"""
Integration tests for hh.ru apply flow.

Требования:
  HH_TOKEN      — hhtoken cookie из user_settings (SELECT hhtoken FROM user_settings LIMIT 1;)
  HH_RESUME_ID  — чистый resume_id (SELECT resume_id FROM user_settings LIMIT 1;)
  HH_VACANCY_ID — ID вакансии для тестового отклика (возьмите любую из vacancies_seen)

Запуск:
  HH_TOKEN='...' HH_RESUME_ID=... HH_VACANCY_ID=... pytest tests/integration/ -v -m integration -s

Тесты НЕ запускаются в обычном CI, только при наличии переменных окружения.
"""
from __future__ import annotations

import json
import os

import httpx
import pytest

from src.hh.client import HHClient, WEB_BASE

HH_TOKEN = os.getenv("HH_TOKEN", "")
HH_RESUME_ID = os.getenv("HH_RESUME_ID", "")
HH_VACANCY_ID = os.getenv("HH_VACANCY_ID", "")

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/122.0.0.0 Safari/537.36"
)


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


pytestmark = pytest.mark.integration


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
async def test_apply_raw_response(hh_client: HHClient) -> None:
    """
    ДИАГНОСТИКА: печатает сырой ответ сервера при отклике.
    Позволяет увидеть реальный body и понять, почему отклик не проходит.
    Тест всегда проходит — он только выводит данные.
    """
    _require_env()

    # Загружаем страницу вакансии для XSRF
    vacancy_url = f"{WEB_BASE}/vacancy/{HH_VACANCY_ID}"
    resp = await hh_client._get(vacancy_url)
    xsrf = hh_client._xsrf()
    print(f"\n--- vacancy page status: {resp.status_code} ---")
    print(f"xsrf: {xsrf!r}")
    print(f"cookies: {list(hh_client._client.cookies.jar)}")

    if not xsrf:
        print("WARN: нет XSRF токена после GET вакансии — проверь hhtoken")
        return

    clean_resume_id = HH_RESUME_ID.split("?")[0]
    print(f"resume_id (clean): {clean_resume_id!r}")
    print(f"vacancy_id: {HH_VACANCY_ID!r}")

    post_resp = await hh_client._client.post(
        f"{WEB_BASE}/applicant/vacancy_response",
        data={
            "vacancy_id": HH_VACANCY_ID,
            "resume_id": clean_resume_id,
            "letter": "",
            "_xsrf": xsrf,
            "ignore_postponed": "1",
        },
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Referer": vacancy_url,
            "X-XSRFToken": xsrf,
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "application/json, text/javascript, */*; q=0.01",
        },
    )
    print(f"\n--- POST /applicant/vacancy_response ---")
    print(f"status: {post_resp.status_code}")
    print(f"final url: {post_resp.url}")
    print(f"response headers: {dict(post_resp.headers)}")
    try:
        body = post_resp.json()
        print(f"body (json):\n{json.dumps(body, ensure_ascii=False, indent=2)}")
    except Exception:
        print(f"body (text, first 1000):\n{post_resp.text[:1000]}")


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
