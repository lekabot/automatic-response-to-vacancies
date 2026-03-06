"""
HH.ru HTTP client.

Два режима:
  1. Поиск вакансий — публичный api.hh.ru (без авторизации, rate-limited).
  2. Автоматический отклик — сессия на hh.ru (cookie-based auth).
"""
from __future__ import annotations

import asyncio
import time
from typing import Any

import httpx
import structlog
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from src.hh.schemas import VacanciesResponse, VacancySchema

log = structlog.get_logger(__name__)

API_BASE = "https://api.hh.ru"
WEB_BASE = "https://hh.ru"


# ---------------------------------------------------------------------------
# Rate Limiter (token bucket)
# ---------------------------------------------------------------------------


class RateLimiter:
    """Token-bucket rate limiter для async-кода."""

    def __init__(self, qps: float, burst: int) -> None:
        self._qps = qps
        self._burst = float(burst)
        self._tokens = float(burst)
        self._last = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            now = time.monotonic()
            self._tokens = min(self._burst, self._tokens + (now - self._last) * self._qps)
            self._last = now
            if self._tokens < 1.0:
                sleep_for = (1.0 - self._tokens) / self._qps
                await asyncio.sleep(sleep_for)
                self._tokens = 0.0
            else:
                self._tokens -= 1.0


# ---------------------------------------------------------------------------
# Retry decorator
# ---------------------------------------------------------------------------


def _retryable(fn):  # type: ignore[no-untyped-def]
    return retry(
        retry=retry_if_exception_type((httpx.HTTPStatusError, httpx.TransportError)),
        stop=stop_after_attempt(4),
        wait=wait_exponential(multiplier=1, min=2, max=30),
        reraise=True,
    )(fn)


# ---------------------------------------------------------------------------
# HH Client
# ---------------------------------------------------------------------------


class HHClient:
    """
    Асинхронный клиент для hh.ru.

    Использование:
        async with HHClient(config) as client:
            await client.login(email, password)
            vacancies = await client.search(text="Python", area=[1])
            await client.apply(vacancy_id="12345", resume_id="abc", letter="...")
    """

    def __init__(
        self,
        user_agent: str,
        qps: float = 2.0,
        burst: int = 5,
    ) -> None:
        self._rate_limiter = RateLimiter(qps=qps, burst=burst)
        headers = {
            "User-Agent": user_agent,
            "Accept": "application/json, text/html,*/*",
            "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.8",
        }
        self._client = httpx.AsyncClient(
            headers=headers,
            follow_redirects=True,
            timeout=httpx.Timeout(30.0),
        )
        self._logged_in = False

    async def __aenter__(self) -> "HHClient":
        return self

    async def __aexit__(self, *_: Any) -> None:
        await self._client.aclose()

    async def aclose(self) -> None:
        await self._client.aclose()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _get(self, url: str, **kwargs: Any) -> httpx.Response:
        await self._rate_limiter.acquire()
        resp = await self._client.get(url, **kwargs)
        resp.raise_for_status()
        return resp

    async def _post(self, url: str, **kwargs: Any) -> httpx.Response:
        await self._rate_limiter.acquire()
        resp = await self._client.post(url, **kwargs)
        resp.raise_for_status()
        return resp

    def _xsrf(self) -> str | None:
        return self._client.cookies.get("_xsrf")

    # ------------------------------------------------------------------
    # Auth
    # ------------------------------------------------------------------

    async def login(self, username: str, password: str) -> bool:
        """
        Авторизация на hh.ru через web-сессию.

        Шаги:
          1. GET /account/login — получаем страницу и XSRF-токен из cookies/HTML.
          2. POST /account/login — отправляем учётные данные.
          3. Проверяем, что редирект ушёл на корневую страницу (значит, логин успешен).
        """
        if not username or not password:
            log.warning("hh.login.skipped", reason="credentials_not_set")
            return False

        try:
            # Шаг 1: получить страницу входа
            resp = await self._get(f"{WEB_BASE}/account/login")
            xsrf = self._xsrf() or self._extract_xsrf_from_html(resp.text)

            if not xsrf:
                log.error("hh.login.failed", reason="xsrf_not_found")
                return False

            # Шаг 2: отправить форму авторизации
            login_resp = await self._client.post(
                f"{WEB_BASE}/account/login",
                data={
                    "backurl": "/",
                    "username": username,
                    "password": password,
                    "_xsrf": xsrf,
                    "remember": "yes",
                    "action": "login",
                },
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Referer": f"{WEB_BASE}/account/login",
                    "X-XSRFToken": xsrf,
                },
                follow_redirects=True,
            )
            login_resp.raise_for_status()

            # Шаг 3: проверить успех — hhtoken cookie должен появиться
            if self._client.cookies.get("hhtoken"):
                self._logged_in = True
                log.info("hh.login.ok", username=username)
                return True

            # Альтернативная проверка: редирект не на /account/login
            if "/account/login" not in str(login_resp.url):
                self._logged_in = True
                log.info("hh.login.ok", username=username)
                return True

            log.warning("hh.login.failed", reason="no_hhtoken_cookie", url=str(login_resp.url))
            return False

        except httpx.HTTPStatusError as exc:
            log.error("hh.login.error", status=exc.response.status_code)
            return False
        except Exception as exc:
            log.exception("hh.login.exception", error=str(exc))
            return False

    def _extract_xsrf_from_html(self, html: str) -> str | None:
        """Извлекает XSRF-токен из скрытого поля формы."""
        from bs4 import BeautifulSoup

        soup = BeautifulSoup(html, "lxml")
        inp = soup.find("input", {"name": "_xsrf"})
        if inp and inp.get("value"):
            return str(inp["value"])
        return None

    # ------------------------------------------------------------------
    # Resumes
    # ------------------------------------------------------------------

    async def get_resumes(self) -> list[dict]:
        """Возвращает список резюме текущего пользователя (парсит HTML)."""
        try:
            resp = await self._get(f"{WEB_BASE}/applicant/resumes")
            return self._parse_resume_list(resp.text)
        except Exception as exc:
            log.warning("hh.resumes.error", error=str(exc))
            return []

    def _parse_resume_list(self, html: str) -> list[dict]:
        from bs4 import BeautifulSoup

        soup = BeautifulSoup(html, "lxml")
        resumes = []

        # Ищем ссылки вида /resume/<id>
        for link in soup.find_all("a", href=True):
            href = str(link["href"])
            if "/resume/" in href and "edit" not in href:
                resume_id = href.rstrip("/").split("/")[-1]
                title = link.get_text(strip=True) or "Резюме"
                if resume_id not in [r["id"] for r in resumes]:
                    resumes.append({"id": resume_id, "title": title})

        return resumes

    # ------------------------------------------------------------------
    # Search
    # ------------------------------------------------------------------

    @_retryable
    async def search_page(
        self,
        *,
        text: str,
        area: list[int],
        schedule: list[str] | None = None,
        employment: list[str] | None = None,
        period: int = 1,
        page: int = 0,
        per_page: int = 50,
    ) -> VacanciesResponse:
        """Одна страница поиска через публичный API hh.ru."""
        params: dict[str, Any] = {
            "text": text,
            "area": area,
            "period": period,
            "page": page,
            "per_page": per_page,
        }
        if schedule:
            params["schedule"] = schedule
        if employment:
            params["employment"] = employment

        resp = await self._get(
            f"{API_BASE}/vacancies",
            params=params,
        )
        return VacanciesResponse.model_validate(resp.json())

    async def search_all(
        self,
        *,
        text: str,
        area: list[int],
        schedule: list[str] | None = None,
        employment: list[str] | None = None,
        period: int = 1,
        max_vacancies: int = 200,
    ) -> list[VacancySchema]:
        """Собирает все страницы поиска до лимита."""
        all_vacancies: list[VacancySchema] = []
        page = 0
        per_page = 50

        while len(all_vacancies) < max_vacancies:
            result = await self.search_page(
                text=text,
                area=area,
                schedule=schedule,
                employment=employment,
                period=period,
                page=page,
                per_page=per_page,
            )
            all_vacancies.extend(result.items)
            log.debug(
                "hh.search.page",
                page=page,
                count=len(result.items),
                total=result.found,
            )
            if page >= result.pages - 1 or not result.items:
                break
            page += 1

        return all_vacancies[:max_vacancies]

    # ------------------------------------------------------------------
    # Apply
    # ------------------------------------------------------------------

    async def apply_to_vacancy(
        self,
        *,
        vacancy_id: str,
        resume_id: str,
        letter: str,
    ) -> bool:
        """
        Отправляет отклик на вакансию через web-сессию hh.ru.

        Алгоритм:
          1. Открываем страницу вакансии, получаем актуальный XSRF.
          2. Отправляем POST на /applicant/vacancy_response.
          3. Проверяем ответ (редирект или JSON-статус).
        """
        if not self._logged_in:
            log.error("hh.apply.not_logged_in")
            return False

        try:
            # Получить свежий XSRF
            await self._get(f"{WEB_BASE}/vacancy/{vacancy_id}")
            xsrf = self._xsrf()
            if not xsrf:
                log.error("hh.apply.no_xsrf", vacancy_id=vacancy_id)
                return False

            resp = await self._client.post(
                f"{WEB_BASE}/applicant/vacancy_response",
                data={
                    "vacancy_id": vacancy_id,
                    "resume_id": resume_id,
                    "letter": letter,
                    "_xsrf": xsrf,
                    "ignore_postponed": "1",
                },
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Referer": f"{WEB_BASE}/vacancy/{vacancy_id}",
                    "X-XSRFToken": xsrf,
                    "X-Requested-With": "XMLHttpRequest",
                },
            )

            # hh.ru может вернуть 200 с JSON {"status": "ok"} или редирект
            if resp.status_code in (200, 302, 303):
                log.info("hh.apply.ok", vacancy_id=vacancy_id, status=resp.status_code)
                return True

            log.warning("hh.apply.unexpected_status", status=resp.status_code, body=resp.text[:300])
            return False

        except httpx.HTTPStatusError as exc:
            log.error(
                "hh.apply.http_error",
                vacancy_id=vacancy_id,
                status=exc.response.status_code,
                body=exc.response.text[:300],
            )
            return False
        except Exception as exc:
            log.exception("hh.apply.exception", vacancy_id=vacancy_id, error=str(exc))
            return False
