"""HH.ru HTTP client — cookie-based web session (login + apply) + public search API."""
from __future__ import annotations

import asyncio
import re
import time
from typing import Any

import httpx
import structlog
from bs4 import BeautifulSoup
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


class RateLimiter:
    """Token-bucket rate limiter."""

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
                await asyncio.sleep((1.0 - self._tokens) / self._qps)
                self._tokens = 0.0
            else:
                self._tokens -= 1.0


def _retryable(fn):  # type: ignore[no-untyped-def]
    return retry(
        retry=retry_if_exception_type((httpx.HTTPStatusError, httpx.TransportError)),
        stop=stop_after_attempt(4),
        wait=wait_exponential(multiplier=1, min=2, max=30),
        reraise=True,
    )(fn)


class HHClient:
    """
    Асинхронный клиент для hh.ru.

    Авторизация — cookie-based web session (email + пароль).
    Поиск — публичный api.hh.ru (без авторизации).
    Отклик — через web-сессию POST /applicant/vacancy_response.

    Использование:
        async with HHClient(user_agent="MyBot/1.0") as client:
            ok = await client.login("user@mail.ru", "password")
            resumes = await client.get_resumes()
            vacancies = await client.search_all(text="Python", area=[1])
            applied = await client.apply(vacancy_id="12345", resume_id="abc")
    """

    def __init__(self, user_agent: str, qps: float = 2.0, burst: int = 5) -> None:
        self._rate = RateLimiter(qps=qps, burst=burst)
        self._client = httpx.AsyncClient(
            headers={
                "User-Agent": user_agent,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "ru-RU,ru;q=0.9",
            },
            follow_redirects=True,
            timeout=httpx.Timeout(30.0),
        )
        self._logged_in = False

    async def __aenter__(self) -> "HHClient":
        return self

    async def __aexit__(self, *_: Any) -> None:
        await self._client.aclose()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _get(self, url: str, **kwargs: Any) -> httpx.Response:
        await self._rate.acquire()
        resp = await self._client.get(url, **kwargs)
        resp.raise_for_status()
        return resp

    def _xsrf(self) -> str | None:
        return self._client.cookies.get("_xsrf")

    def _extract_xsrf(self, html: str) -> str | None:
        """Извлекает XSRF из HTML-input (старый формат) или из page-JSON (React SPA)."""
        # React SPA: "xsrfToken":"<hex32>"
        m = re.search(r'"xsrfToken"\s*:\s*"([a-f0-9]{32})"', html)
        if m:
            return m.group(1)
        # Legacy: <input name="_xsrf" value="...">
        soup = BeautifulSoup(html, "lxml")
        inp = soup.find("input", {"name": "_xsrf"})
        return str(inp["value"]) if inp and inp.get("value") else None

    # ------------------------------------------------------------------
    # Auth
    # ------------------------------------------------------------------

    async def login(self, email: str, password: str) -> bool:
        """
        Авторизация через web-сессию hh.ru (актуальный flow из JS-источников).

        1. GET hh.ru/ — устанавливаем сессионные куки (hhuid, _xsrf и др.).
        2. GET /account/login — получаем XSRF.
        3. POST /account/otp_generate — инициируем авторизацию.
           Сервер вернёт JSON:
             {"key": "PASSWORD_REQUIRED", "redirectURL": "..."} — нужен пароль
             {"otp": {...}}                                      — нужен OTP-код
        4. Если PASSWORD_REQUIRED → GET redirectURL → POST пароль.
        5. Проверяем cookie hhtoken.
        """
        if not email or not password:
            log.warning("hh.login.skipped", reason="empty_credentials")
            return False

        _login_url = f"{WEB_BASE}/account/login?role=applicant&backurl=%2F"

        try:
            # Устанавливаем сессионные куки
            await self._rate.acquire()
            await self._client.get(f"{WEB_BASE}/", follow_redirects=True)

            # Получаем XSRF
            resp = await self._get(_login_url)
            xsrf = self._xsrf() or self._extract_xsrf(resp.text)
            if not xsrf:
                log.error("hh.login.no_xsrf")
                return False

            # POST /account/otp_generate — шаг 1 нового flow hh.ru
            otp_resp = await self._client.post(
                f"{WEB_BASE}/account/otp_generate",
                data={
                    "login": email,
                    "backurl": "/",
                    "operationType": "applicant_otp_auth",
                    "role": "applicant",
                    "formatPhone": "true",
                    "_xsrf": xsrf,
                },
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Referer": _login_url,
                    "X-XSRFToken": xsrf,
                    "Accept": "application/json",
                    "X-Requested-With": "XMLHttpRequest",
                },
                follow_redirects=True,
            )
            xsrf = self._xsrf() or xsrf  # обновляем XSRF

            try:
                otp_data = otp_resp.json()
            except Exception:
                otp_data = {}

            log.info("hh.login.otp_generate", status=otp_resp.status_code, data=otp_data)

            key = otp_data.get("key")
            if key != "PASSWORD_REQUIRED":
                # OTP отправлен на почту/телефон — нельзя автоматизировать
                log.error(
                    "hh.login.otp_required",
                    key=key,
                    hint="account requires OTP code, password login unavailable",
                )
                return False

            # Шаг 2: переходим на страницу ввода пароля
            redirect_url = otp_data.get("redirectURL") or _login_url
            log.info("hh.login.password_step", redirect_url=redirect_url)

            await self._client.get(redirect_url, follow_redirects=True)
            xsrf = self._xsrf() or xsrf

            # Шаг 3: отправляем пароль
            login_resp = await self._client.post(
                redirect_url,
                data={
                    "login": email,
                    "password": password,
                    "_xsrf": xsrf,
                    "backUrl": "/",
                    "remember": "yes",
                },
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Referer": redirect_url,
                    "X-XSRFToken": xsrf,
                },
                follow_redirects=True,
            )

            if login_resp.status_code >= 400:
                log.error(
                    "hh.login.bad_response",
                    status=login_resp.status_code,
                    body=login_resp.text[:300],
                )
                return False

            self._logged_in = bool(self._client.cookies.get("hhtoken")) or (
                "/account/login" not in str(login_resp.url)
            )
            log.info(
                "hh.login.result",
                success=self._logged_in,
                final_url=str(login_resp.url),
                has_hhtoken=bool(self._client.cookies.get("hhtoken")),
            )
            return self._logged_in

        except Exception as exc:
            log.error("hh.login.error", error=str(exc), exc_info=exc)
            return False

    # ------------------------------------------------------------------
    # Resumes (parse HTML /applicant/resumes)
    # ------------------------------------------------------------------

    async def get_resumes(self) -> list[dict]:
        """Список резюме текущего пользователя (парсинг /applicant/resumes)."""
        try:
            resp = await self._get(f"{WEB_BASE}/applicant/resumes")
            return self._parse_resumes(resp.text)
        except Exception as exc:
            log.warning("hh.get_resumes.error", error=str(exc))
            return []

    def _parse_resumes(self, html: str) -> list[dict]:
        soup = BeautifulSoup(html, "lxml")
        resumes: list[dict] = []
        seen: set[str] = set()
        for link in soup.find_all("a", href=True):
            href = str(link["href"])
            if "/resume/" in href and "edit" not in href:
                rid = href.rstrip("/").split("/")[-1]
                if rid not in seen and len(rid) > 4:
                    seen.add(rid)
                    resumes.append({"id": rid, "title": link.get_text(strip=True) or "Резюме"})
        return resumes

    # ------------------------------------------------------------------
    # Search (публичный API, авторизация не нужна)
    # ------------------------------------------------------------------

    @_retryable
    async def _search_page(
        self,
        *,
        text: str,
        area: list[int],
        schedule: list[str] | None,
        employment: list[str] | None,
        period: int,
        page: int,
        per_page: int = 50,
    ) -> VacanciesResponse:
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

        await self._rate.acquire()
        resp = await self._client.get(f"{API_BASE}/vacancies", params=params)
        resp.raise_for_status()
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
        """Собирает все страницы поиска до лимита max_vacancies."""
        vacancies: list[VacancySchema] = []
        page = 0
        while len(vacancies) < max_vacancies:
            result = await self._search_page(
                text=text, area=area, schedule=schedule,
                employment=employment, period=period, page=page,
            )
            vacancies.extend(result.items)
            if page >= result.pages - 1 or not result.items:
                break
            page += 1
        return vacancies[:max_vacancies]

    # ------------------------------------------------------------------
    # Apply (web session, требует login())
    # ------------------------------------------------------------------

    async def apply(self, *, vacancy_id: str, resume_id: str, letter: str = "") -> bool:
        """
        Откликнуться на вакансию через web-сессию.

        Требует предварительного вызова login().
        """
        if not self._logged_in:
            log.error("hh.apply.not_logged_in", vacancy_id=vacancy_id)
            return False
        try:
            # Обновляем XSRF-токен, открыв страницу вакансии
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
            ok = resp.status_code in (200, 302, 303)
            log.info("hh.apply.result", vacancy_id=vacancy_id, status=resp.status_code, ok=ok)
            return ok

        except Exception as exc:
            log.error("hh.apply.error", vacancy_id=vacancy_id, error=str(exc), exc_info=exc)
            return False
