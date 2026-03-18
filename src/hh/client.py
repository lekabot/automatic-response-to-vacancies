"""HH.ru HTTP client — cookie session (login + apply) + public search API."""
from __future__ import annotations

import asyncio
import json
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

from src.hh.apply_types import ApplyOutcome, ApplyStatus
from src.hh.schemas import VacanciesResponse, VacancySchema
from src.hh.session_status import SessionValidationStatus

log = structlog.get_logger(__name__)

API_BASE = "https://api.hh.ru"
WEB_BASE = "https://hh.ru"

APPLY_MAX_RETRIES = 3
APPLY_BACKOFF_BASE = 1.5
APPLY_BODY_SNIPPET = 400
_CHALLENGE_MARKERS = (
    "captcha",
    "smartcaptcha",
    "hcaptcha",
    "yandex.smartcaptcha",
    "пройдите проверку",
    "подтвердите, что вы не робот",
    "challenge",
    "antibot",
    "rate limit",
    "слишком много запросов",
)


def _apply_jitter_delay(attempt: int) -> float:
    import random

    base = APPLY_BACKOFF_BASE ** attempt
    return base + random.uniform(0, 0.8)


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


def _classify_apply_html(
    *,
    vacancy_id: str,
    body_text: str,
    http_status: int,
) -> ApplyOutcome | None:
    low = body_text[:30000].lower()
    if any(m in low for m in _CHALLENGE_MARKERS):
        log.warning(
            "hh.apply.challenge_detected",
            vacancy_id=vacancy_id,
            http_status=http_status,
        )
        return ApplyOutcome(
            status=ApplyStatus.TEMP_ERROR,
            http_status=http_status,
            error_code="challenge_or_captcha",
            error_message=None,
            retryable=True,
        )
    if "account/login" in low or (
        "войдите" in low and ("пароль" in low or "password" in low or "логин" in low)
    ):
        log.warning(
            "hh.session.invalid",
            context="apply_response_html",
            vacancy_id=vacancy_id,
            http_status=http_status,
        )
        return ApplyOutcome(
            status=ApplyStatus.AUTH_ERROR,
            http_status=http_status,
            error_code="session_expired_html",
            error_message=None,
            retryable=False,
        )
    if http_status >= 500 or "502 bad gateway" in low or "503 service unavailable" in low:
        return ApplyOutcome(
            status=ApplyStatus.TEMP_ERROR,
            http_status=http_status,
            error_code="server_error_html",
            error_message=None,
            retryable=True,
        )
    if "<html" in low[:2000] or body_text.strip().startswith("<!"):
        log.warning(
            "hh.apply.classified_error",
            vacancy_id=vacancy_id,
            status=ApplyStatus.TEMP_ERROR.value,
            http_status=http_status,
            error_code="html_not_json",
            retryable=True,
        )
        return ApplyOutcome(
            status=ApplyStatus.TEMP_ERROR,
            http_status=http_status,
            error_code="html_not_json",
            error_message=None,
            retryable=True,
        )
    return None


def _classify_apply_response(
    *,
    vacancy_id: str,
    resp: httpx.Response,
    body_text: str,
    parsed: dict[str, Any] | None,
) -> ApplyOutcome:
    code = resp.status_code
    if code == 429:
        log.warning("hh.apply.rate_limited", vacancy_id=vacancy_id, http_status=429)
        return ApplyOutcome(
            status=ApplyStatus.TEMP_ERROR,
            http_status=429,
            error_code="http_429",
            error_message=None,
            retryable=True,
        )
    if code in (401, 403):
        log.warning(
            "hh.apply.classified_error",
            vacancy_id=vacancy_id,
            status=ApplyStatus.AUTH_ERROR.value,
            http_status=code,
            retryable=False,
            error_code="http_auth",
            snippet=body_text[:APPLY_BODY_SNIPPET],
        )
        return ApplyOutcome(
            status=ApplyStatus.AUTH_ERROR,
            http_status=code,
            error_code="http_auth",
            error_message=body_text[:200],
            retryable=False,
        )
    if code >= 500:
        log.warning(
            "hh.apply.classified_error",
            vacancy_id=vacancy_id,
            status=ApplyStatus.TEMP_ERROR.value,
            http_status=code,
            retryable=True,
            error_code="http_5xx",
        )
        return ApplyOutcome(
            status=ApplyStatus.TEMP_ERROR,
            http_status=code,
            error_code="http_5xx",
            error_message=None,
            retryable=True,
        )

    if parsed is not None:
        err = parsed.get("error") or parsed.get("errors")
        if err == "alreadyApplied" or (
            isinstance(err, list) and any(
                isinstance(e, dict) and e.get("value") == "alreadyApplied" for e in err
            )
        ):
            log.info("hh.apply.response", vacancy_id=vacancy_id, http_status=code, outcome="already_applied")
            return ApplyOutcome.already_applied()
        if err == "captchaRequired" or (
            isinstance(err, str) and "captcha" in err.lower()
        ):
            log.warning("hh.apply.challenge_detected", vacancy_id=vacancy_id, http_status=code, error=err)
            return ApplyOutcome(
                status=ApplyStatus.TEMP_ERROR,
                http_status=code,
                error_code="captcha_required",
                error_message=str(err),
                retryable=True,
            )
        if isinstance(err, str) and err:
            perm_codes = (
                "validation",
                "invalid",
                "forbidden",
                "xsrf",
                "auth",
                "session",
                "resume",
                "letter",
            )
            low = err.lower()
            if any(p in low for p in perm_codes) or err == "badRequest":
                log.warning(
                    "hh.apply.classified_error",
                    vacancy_id=vacancy_id,
                    status=ApplyStatus.PERM_ERROR.value,
                    http_status=code,
                    retryable=False,
                    error_code=err,
                    snippet=str(parsed)[:APPLY_BODY_SNIPPET],
                )
                return ApplyOutcome(
                    status=ApplyStatus.PERM_ERROR,
                    http_status=code,
                    error_code=err,
                    error_message=str(parsed)[:500],
                    retryable=False,
                )

        ok = code == 200 and str(parsed.get("success", "")).lower() == "true"
        if ok:
            log.info("hh.apply.response", vacancy_id=vacancy_id, http_status=code, outcome="applied")
            return ApplyOutcome.applied()

    html_class = _classify_apply_html(
        vacancy_id=vacancy_id, body_text=body_text, http_status=code
    )
    if html_class is not None:
        return html_class

    log.warning(
        "hh.apply.classified_error",
        vacancy_id=vacancy_id,
        status=ApplyStatus.TEMP_ERROR.value,
        http_status=code,
        retryable=True,
        error_code="unclassified_response",
        snippet=body_text[:APPLY_BODY_SNIPPET],
    )
    return ApplyOutcome(
        status=ApplyStatus.TEMP_ERROR,
        http_status=code,
        error_code="unclassified_response",
        error_message=body_text[:300],
        retryable=True,
    )


class HHClient:
    """
    Асинхронный клиент для hh.ru.

    Авторизация — cookie-based web session.
    Поиск — api.hh.ru.
    Отклик — POST /applicant/vacancy_response/popup.
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

    async def __aenter__(self) -> HHClient:
        return self

    async def __aexit__(self, *_: Any) -> None:
        await self._client.aclose()

    async def _get(self, url: str, **kwargs: Any) -> httpx.Response:
        await self._rate.acquire()
        resp = await self._client.get(url, **kwargs)
        resp.raise_for_status()
        return resp

    def _cookie(self, name: str) -> str | None:
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
        m = re.search(r'"xsrfToken"\s*:\s*"([a-f0-9]{32})"', html)
        if m:
            return m.group(1)
        soup = BeautifulSoup(html, "lxml")
        inp = soup.find("input", {"name": "_xsrf"})
        return str(inp["value"]) if inp and inp.get("value") else None

    async def initiate_login(self, email: str) -> dict[str, Any]:
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
            if key in ("CODE_SEND_OK", "OTP_SEND_OK", "CODE_SEND_BLOCKED"):
                already_sent = key == "CODE_SEND_BLOCKED"
                return {
                    "method": "otp",
                    "cookies": cookies,
                    "xsrf": xsrf,
                    "notification_type": otp_data.get("notificationType") or "EMAIL",
                    "already_sent": already_sent,
                }
            log.error("hh.login.unknown_key", key=key, data=otp_data)
            return {"method": "error", "message": f"Неожиданный ответ от hh.ru: key={key!r}"}

        except Exception as exc:
            log.error("hh.login.initiate_error", error=str(exc), exc_info=exc)
            return {"method": "error", "message": str(exc)}

    async def complete_password_login(self, email: str, password: str, login_info: dict[str, Any]) -> bool:
        """Завершение входа по паролю после PASSWORD_REQUIRED."""
        self.restore_cookies(login_info.get("cookies", {}))
        referer = str(login_info.get("redirect_url") or f"{WEB_BASE}/account/login?role=applicant")
        xsrf = login_info.get("xsrf") or self._xsrf() or ""
        if not xsrf:
            await self._rate.acquire()
            r0 = await self._client.get(referer, follow_redirects=True)
            xsrf = self._xsrf() or self._extract_xsrf(r0.text) or ""
        try:
            await self._rate.acquire()
            resp = await self._client.post(
                f"{WEB_BASE}/account/login",
                data={
                    "username": email,
                    "password": password,
                    "_xsrf": xsrf,
                    "remember": "true",
                    "accountType": "APPLICANT",
                    "isApplicantSignup": "false",
                    "backurl": "/",
                },
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Referer": referer,
                    "X-XSRFToken": xsrf,
                    "Accept": "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With": "XMLHttpRequest",
                },
                follow_redirects=True,
            )
            self._logged_in = bool(self._cookie("hhtoken"))
            if not self._logged_in and resp.status_code == 200:
                try:
                    data = resp.json()
                    if data.get("success") is True or data.get("key") == "LOGIN_SUCCESS":
                        self._logged_in = bool(self._cookie("hhtoken"))
                except Exception:
                    pass
            log.info(
                "hh.login.password_complete",
                status=resp.status_code,
                success=self._logged_in,
            )
            return self._logged_in
        except Exception as exc:
            log.error("hh.login.password_error", error=str(exc), exc_info=exc)
            return False

    async def login(self, email: str, password: str) -> bool:
        """
        Вход по паролю для пайплайна (без OTP-кода).
        Для аккаунтов только с OTP нужен сохранённый hhtoken.
        """
        if not (password or "").strip():
            log.warning("hh.login.empty_password", email=email[:3] + "***")
            return False
        init = await self.initiate_login(email)
        if init.get("method") == "error":
            return False
        if init.get("method") == "password":
            return await self.complete_password_login(email, password, init)
        if init.get("method") == "otp":
            log.warning(
                "hh.login.otp_only_no_password_flow",
                hint="save_hhtoken_via_bot",
            )
            return False
        return False

    def restore_cookies(self, cookies: dict[str, Any]) -> None:
        for name, value in cookies.items():
            if value is None:
                continue
            v = str(value)
            try:
                self._client.cookies.set(name, v, domain="hh.ru")
            except Exception:
                self._client.cookies.set(name, v)

    def get_hhtoken(self) -> str | None:
        return self._cookie("hhtoken")

    async def complete_otp_login(self, email: str, code: str) -> bool:
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

    async def validate_session_status(self) -> SessionValidationStatus:
        """
        Проверка сессии: INVALID только при явно мёртвой авторизации;
        временные сбои hh.ru — TEMP_UNAVAILABLE (не считать протухшим токен).
        """
        if not self._cookie("hhtoken"):
            log.warning("hh.session.invalid", reason="missing_hhtoken")
            return SessionValidationStatus.INVALID
        try:
            await self._rate.acquire()
            resp = await self._client.get(
                f"{WEB_BASE}/applicant/resumes",
                follow_redirects=True,
                timeout=httpx.Timeout(20.0),
            )
        except httpx.TimeoutException as exc:
            log.warning("hh.session.temp_unavailable", reason="timeout", error=str(exc))
            return SessionValidationStatus.TEMP_UNAVAILABLE
        except httpx.TransportError as exc:
            log.warning("hh.session.temp_unavailable", reason="transport", error=str(exc))
            return SessionValidationStatus.TEMP_UNAVAILABLE
        except Exception as exc:
            log.warning("hh.session.temp_unavailable", reason="request_error", error=str(exc))
            return SessionValidationStatus.TEMP_UNAVAILABLE

        url = str(resp.url).lower()
        body_low = resp.text[:25000].lower()

        if resp.status_code == 429:
            log.warning("hh.session.temp_unavailable", reason="http_429")
            return SessionValidationStatus.TEMP_UNAVAILABLE
        if resp.status_code >= 500:
            log.warning("hh.session.temp_unavailable", reason="http_5xx", http_status=resp.status_code)
            return SessionValidationStatus.TEMP_UNAVAILABLE

        if resp.status_code in (401, 403):
            log.warning("hh.session.invalid", reason="http_auth", http_status=resp.status_code)
            return SessionValidationStatus.INVALID
        if "account/login" in url:
            log.warning("hh.session.invalid", reason="login_redirect_url", http_status=resp.status_code)
            return SessionValidationStatus.INVALID

        session_dead_markers = (
            "сессия истекла",
            "session has expired",
            "session expired",
            "необходимо войти",
            "authorization required",
        )
        if any(m in body_low for m in session_dead_markers) and "account/login" in body_low:
            log.warning("hh.session.invalid", reason="session_expired_marker")
            return SessionValidationStatus.INVALID

        if any(m in body_low for m in _CHALLENGE_MARKERS):
            log.warning("hh.session.temp_unavailable", reason="challenge_or_antibot_page")
            return SessionValidationStatus.TEMP_UNAVAILABLE

        if resp.status_code == 200 and "/applicant/" in url:
            return SessionValidationStatus.VALID

        log.warning(
            "hh.session.temp_unavailable",
            reason="unexpected_response",
            http_status=resp.status_code,
        )
        return SessionValidationStatus.TEMP_UNAVAILABLE

    async def validate_session(self) -> bool:
        """Обратная совместимость: True только при VALID."""
        return (await self.validate_session_status()) is SessionValidationStatus.VALID

    async def get_resumes(self) -> list[dict[str, Any]]:
        try:
            resp = await self._get(f"{WEB_BASE}/applicant/resumes")
            return self._parse_resumes(resp.text)
        except Exception as exc:
            log.warning("hh.get_resumes.error", error=str(exc))
            return []

    def _parse_resumes(self, html: str) -> list[dict[str, Any]]:
        soup = BeautifulSoup(html, "lxml")
        resumes: list[dict[str, Any]] = []
        seen: set[str] = set()
        for link in soup.find_all("a", href=True):
            href = str(link["href"])
            if "/resume/" not in href or "edit" in href:
                continue
            clean_href = href.split("?")[0]
            rid = clean_href.rstrip("/").split("/")[-1]
            if rid not in seen and len(rid) > 4:
                seen.add(rid)
                strings = [s.strip() for s in link.strings if s.strip()]
                title = strings[0] if strings else "Резюме"
                resumes.append({"id": rid, "title": title})
        return resumes

    @_retryable
    async def _search_page(
        self,
        *,
        text: str,
        area: list[int],
        schedule: list[str] | None,
        employment: list[str] | None,
        search_field: list[str] | None,
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
        if search_field:
            params["search_field"] = search_field

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
        search_field: list[str] | None = None,
        period: int = 1,
        max_vacancies: int = 200,
    ) -> list[VacancySchema]:
        vacancies: list[VacancySchema] = []
        page = 0
        while len(vacancies) < max_vacancies:
            result = await self._search_page(
                text=text,
                area=area,
                schedule=schedule,
                employment=employment,
                search_field=search_field,
                period=period,
                page=page,
            )
            vacancies.extend(result.items)
            if page >= result.pages - 1 or not result.items:
                break
            page += 1
        return vacancies[:max_vacancies]

    async def _ensure_xsrf(self) -> str | None:
        xsrf = self._xsrf()
        if xsrf:
            return xsrf
        try:
            resp = await self._get(f"{WEB_BASE}/")
            return self._xsrf() or self._extract_xsrf(resp.text)
        except Exception:
            return None

    async def _apply_once(
        self,
        *,
        vacancy_id: str,
        resume_id: str,
        letter: str,
    ) -> ApplyOutcome:
        if not self._logged_in:
            log.error("hh.apply.not_logged_in", vacancy_id=vacancy_id)
            return ApplyOutcome(
                status=ApplyStatus.PERM_ERROR,
                http_status=None,
                error_code="not_logged_in",
                error_message=None,
                retryable=False,
            )

        resume_hash = resume_id.split("?")[0]
        vacancy_url = f"{WEB_BASE}/vacancy/{vacancy_id}"

        try:
            xsrf = await self._ensure_xsrf()
            if not xsrf:
                log.warning(
                    "hh.apply.classified_error",
                    vacancy_id=vacancy_id,
                    status=ApplyStatus.AUTH_ERROR.value,
                    error_code="no_xsrf",
                    retryable=False,
                )
                return ApplyOutcome(
                    status=ApplyStatus.AUTH_ERROR,
                    http_status=None,
                    error_code="no_xsrf",
                    error_message="XSRF/session",
                    retryable=False,
                )

            form: dict[str, str] = {
                "vacancy_id": vacancy_id,
                "resume_hash": resume_hash,
                "ignore_postponed": "true",
                "lux": "true",
            }
            if letter:
                form["letter"] = letter

            log.info(
                "hh.apply.request",
                vacancy_id=vacancy_id,
                resume_hash_prefix=resume_hash[:8],
            )
            await self._rate.acquire()
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
            body_text = resp.text
            parsed: dict[str, Any] | None = None
            try:
                parsed = resp.json()
            except json.JSONDecodeError:
                pass
            return _classify_apply_response(
                vacancy_id=vacancy_id, resp=resp, body_text=body_text, parsed=parsed
            )

        except httpx.TimeoutException as exc:
            log.warning(
                "hh.apply.classified_error",
                vacancy_id=vacancy_id,
                status=ApplyStatus.TIMEOUT.value,
                retryable=True,
                error_code=type(exc).__name__,
            )
            return ApplyOutcome(
                status=ApplyStatus.TIMEOUT,
                http_status=None,
                error_code=type(exc).__name__,
                error_message=str(exc),
                retryable=True,
            )
        except httpx.TransportError as exc:
            log.warning(
                "hh.apply.classified_error",
                vacancy_id=vacancy_id,
                status=ApplyStatus.TEMP_ERROR.value,
                retryable=True,
                error_code=type(exc).__name__,
            )
            return ApplyOutcome(
                status=ApplyStatus.TEMP_ERROR,
                http_status=None,
                error_code=type(exc).__name__,
                error_message=str(exc),
                retryable=True,
            )
        except Exception as exc:
            log.exception("hh.apply.unexpected", vacancy_id=vacancy_id, error=str(exc))
            return ApplyOutcome(
                status=ApplyStatus.PERM_ERROR,
                http_status=None,
                error_code="unexpected",
                error_message=str(exc),
                retryable=False,
            )

    async def apply(
        self,
        *,
        vacancy_id: str,
        resume_id: str,
        letter: str = "",
        per_attempt_timeout: float = 35.0,
    ) -> ApplyOutcome:
        """
        Отклик с ограниченным retry только для сетевых/timeout/5xx ошибок.
        """
        last: ApplyOutcome | None = None
        for attempt in range(APPLY_MAX_RETRIES):
            try:
                outcome = await asyncio.wait_for(
                    self._apply_once(vacancy_id=vacancy_id, resume_id=resume_id, letter=letter),
                    timeout=per_attempt_timeout,
                )
            except asyncio.TimeoutError:
                outcome = ApplyOutcome(
                    status=ApplyStatus.TIMEOUT,
                    http_status=None,
                    error_code="asyncio_timeout",
                    error_message=f"wait_for {per_attempt_timeout}s",
                    retryable=True,
                )
                log.warning(
                    "hh.apply.classified_error",
                    vacancy_id=vacancy_id,
                    status=ApplyStatus.TIMEOUT.value,
                    retryable=True,
                )

            last = outcome
            if outcome.status in (ApplyStatus.APPLIED, ApplyStatus.ALREADY_APPLIED):
                return outcome
            if not outcome.retryable:
                return outcome
            if attempt < APPLY_MAX_RETRIES - 1:
                delay = _apply_jitter_delay(attempt)
                log.info(
                    "hh.apply.retry_backoff",
                    vacancy_id=vacancy_id,
                    attempt=attempt + 1,
                    delay_s=round(delay, 2),
                )
                await asyncio.sleep(delay)

        assert last is not None
        return last
