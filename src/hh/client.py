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

    def __init__(
        self, user_agent: str, qps: float = 2.0, burst: int = 5, hhtoken: str | None = None
    ) -> None:
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
        if hhtoken:
            self._client.cookies.set("hhtoken", hhtoken, domain="hh.ru")
            self._logged_in = True

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

    def _cookie(self, name: str) -> str | None:
        """Возвращает значение куки, игнорируя CookieConflict (дубли по доменам)."""
        try:
            return self._client.cookies.get(name)
        except Exception:
            for cookie in self._client.cookies.jar:
                if cookie.name == name:
                    return cookie.value
            return None

    def _xsrf(self) -> str | None:
        return self._cookie("_xsrf")

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

    async def initiate_login(self, email: str) -> dict:
        """
        Шаг 1 авторизации: отправляет запрос к hh.ru и определяет метод входа.

        Возвращает один из вариантов:
          {"method": "otp",      "cookies": {...}, "xsrf": "..."}
          {"method": "password", "cookies": {...}, "xsrf": "...", "redirect_url": "..."}
          {"method": "error",    "message": "..."}
        """
        _login_url = f"{WEB_BASE}/account/login?role=applicant&backurl=%2F"
        try:
            await self._rate.acquire()
            await self._client.get(f"{WEB_BASE}/", follow_redirects=True)

            resp = await self._get(_login_url)
            xsrf = self._xsrf() or self._extract_xsrf(resp.text)
            if not xsrf:
                log.error("hh.login.no_xsrf")
                return {"method": "error", "message": "Не удалось получить XSRF-токен"}

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
            xsrf = self._xsrf() or xsrf

            try:
                otp_data = otp_resp.json()
            except Exception:
                otp_data = {}

            key = otp_data.get("key", "")
            cookies = dict(self._client.cookies)
            log.info("hh.login.initiate", key=key)

            if key == "PASSWORD_REQUIRED":
                return {
                    "method": "password",
                    "cookies": cookies,
                    "xsrf": xsrf,
                    "redirect_url": otp_data.get("redirectURL") or _login_url,
                }
            elif key in ("CODE_SEND_OK", "OTP_SEND_OK", "CODE_SEND_BLOCKED"):
                # CODE_SEND_BLOCKED — код уже отправлен ранее и ещё действует
                already_sent = key == "CODE_SEND_BLOCKED"
                return {
                    "method": "otp",
                    "cookies": cookies,
                    "xsrf": xsrf,
                    "notification_type": otp_data.get("notificationType") or "EMAIL",
                    "already_sent": already_sent,
                }
            else:
                log.error("hh.login.unknown_key", key=key, data=otp_data)
                return {"method": "error", "message": f"Неожиданный ответ от hh.ru: key={key!r}"}

        except Exception as exc:
            log.error("hh.login.initiate_error", error=str(exc), exc_info=exc)
            return {"method": "error", "message": str(exc)}

    def restore_cookies(self, cookies: dict) -> None:
        """Восстанавливает сессионные куки (для продолжения авторизации в новом клиенте)."""
        for name, value in cookies.items():
            self._client.cookies.set(name, value)

    def get_hhtoken(self) -> str | None:
        """Возвращает значение hhtoken cookie после успешного входа."""
        return self._cookie("hhtoken")

    async def complete_otp_login(self, email: str, code: str) -> bool:
        """
        Шаг 2 для OTP-аккаунтов: проверяет 4-значный код из email/телефона.
        Перед вызовом нужно восстановить куки через restore_cookies().
        """
        _login_url = f"{WEB_BASE}/account/login?role=applicant&backurl=%2F"
        xsrf = self._xsrf()
        try:
            resp = await self._client.post(
                f"{WEB_BASE}/account/login/by_code",
                data={
                    "username": email,
                    "code": code.strip(),
                    "remember": "true",
                    "accountType": "APPLICANT",
                    "isApplicantSignup": "false",
                    "operationType": "otp_auth",
                    "backurl": "/",
                    "_xsrf": xsrf or "",
                },
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Referer": _login_url,
                    "X-XSRFToken": xsrf or "",
                    "Accept": "application/json",
                    "X-Requested-With": "XMLHttpRequest",
                },
                follow_redirects=True,
            )
            self._logged_in = bool(self._cookie("hhtoken"))
            log.info(
                "hh.login.otp_complete",
                status=resp.status_code,
                success=self._logged_in,
            )
            return self._logged_in
        except Exception as exc:
            log.error("hh.login.otp_complete_error", error=str(exc), exc_info=exc)
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
            if "/resume/" not in href or "edit" in href:
                continue
            # Отрезаем query string, чтобы resume_id был чистым
            clean_href = href.split("?")[0]
            rid = clean_href.rstrip("/").split("/")[-1]
            if rid not in seen and len(rid) > 4:
                seen.add(rid)
                # Берём первую непустую текстовую строку внутри тега (не кнопки/вложенные элементы)
                strings = [s.strip() for s in link.strings if s.strip()]
                title = strings[0] if strings else "Резюме"
                resumes.append({"id": rid, "title": title})
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
        Откликнуться на вакансию через /applicant/vacancy_response/popup (FormData).

        Фактический endpoint: POST /applicant/vacancy_response/popup
        Поля: vacancy_id, resume_hash (не resume_id!), ignore_postponed, lux, letter
        Требует предварительного вызова login() или передачи hhtoken в конструктор.
        """
        if not self._logged_in:
            log.error("hh.apply.not_logged_in", vacancy_id=vacancy_id)
            return False

        # Защита от resume_id с query string (из-за старых данных в БД)
        resume_hash = resume_id.split("?")[0]

        try:
            vacancy_url = f"{WEB_BASE}/vacancy/{vacancy_id}"
            await self._get(vacancy_url)
            xsrf = self._xsrf()
            if not xsrf:
                log.error("hh.apply.no_xsrf", vacancy_id=vacancy_id)
                return False

            # hh.ru использует FormData и поле resume_hash (не resume_id)
            form = {
                "vacancy_id": vacancy_id,
                "resume_hash": resume_hash,
                "ignore_postponed": "true",
                "lux": "true",
            }
            if letter:
                form["letter"] = letter

            resp = await self._client.post(
                f"{WEB_BASE}/applicant/vacancy_response/popup",
                data=form,
                headers={
                    "Referer": vacancy_url,
                    "X-XSRFToken": xsrf,
                    "X-Requested-With": "XMLHttpRequest",
                    "Accept": "application/json, text/javascript, */*; q=0.01",
                },
            )
            try:
                body = resp.json()
                error = body.get("error", "")
                if error == "alreadyApplied":
                    # Уже откликались — считаем успехом (цель достигнута)
                    log.info("hh.apply.already_applied", vacancy_id=vacancy_id)
                    return True
                ok = resp.status_code == 200 and body.get("success") == "true"
            except Exception:
                ok = resp.status_code in (200, 201)
                body = resp.text[:400]

            if not ok:
                log.warning("hh.apply.failed", vacancy_id=vacancy_id, status=resp.status_code, body=str(body)[:400])
            log.info("hh.apply.result", vacancy_id=vacancy_id, status=resp.status_code, ok=ok)
            return ok

        except Exception as exc:
            log.error("hh.apply.error", vacancy_id=vacancy_id, error=str(exc), exc_info=exc)
            return False
