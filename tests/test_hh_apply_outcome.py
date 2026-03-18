"""Классификация ответов HHClient.apply (без реального hh.ru)."""
from __future__ import annotations

import httpx
import pytest
import respx

from src.hh.apply_types import ApplyStatus
from src.hh.client import HHClient


async def _patch_xsrf(c: HHClient) -> None:
    async def xsrf() -> str:
        return "0123456789abcdef0123456789abcdef"

    c._ensure_xsrf = xsrf  # type: ignore[method-assign]


@pytest.mark.asyncio
@respx.mock
async def test_apply_outcome_already_applied() -> None:
    respx.post("https://hh.ru/applicant/vacancy_response/popup").mock(
        return_value=httpx.Response(200, json={"error": "alreadyApplied"})
    )
    async with HHClient(user_agent="t", hhtoken="x", qps=100, burst=100) as c:
        await _patch_xsrf(c)
        out = await c.apply(vacancy_id="1", resume_id="abc", letter="", per_attempt_timeout=5.0)
    assert out.status == ApplyStatus.ALREADY_APPLIED
    assert not out.retryable


@pytest.mark.asyncio
@respx.mock
async def test_apply_outcome_429_rate_limit() -> None:
    respx.post("https://hh.ru/applicant/vacancy_response/popup").mock(
        return_value=httpx.Response(429, text="Too Many Requests")
    )
    async with HHClient(user_agent="t", hhtoken="x", qps=100, burst=100) as c:
        await _patch_xsrf(c)
        out = await c.apply(vacancy_id="1", resume_id="abc", letter="", per_attempt_timeout=5.0)
    assert out.status == ApplyStatus.TEMP_ERROR
    assert out.retryable
    assert out.http_status == 429


@pytest.mark.asyncio
@respx.mock
async def test_apply_challenge_html_not_perm() -> None:
    html = "<!DOCTYPE html><html><body>yandex.smartcaptcha verify</body></html>"
    respx.post("https://hh.ru/applicant/vacancy_response/popup").mock(
        return_value=httpx.Response(200, text=html, headers={"Content-Type": "text/html"})
    )
    async with HHClient(user_agent="t", hhtoken="x", qps=100, burst=100) as c:
        await _patch_xsrf(c)
        out = await c.apply(vacancy_id="1", resume_id="abc", letter="", per_attempt_timeout=5.0)
    assert out.status == ApplyStatus.TEMP_ERROR
    assert out.retryable
    assert out.error_code == "challenge_or_captcha"


@pytest.mark.asyncio
@respx.mock
async def test_apply_outcome_503_retryable() -> None:
    respx.post("https://hh.ru/applicant/vacancy_response/popup").mock(
        return_value=httpx.Response(503, text="bad")
    )
    async with HHClient(user_agent="t", hhtoken="x", qps=100, burst=100) as c:
        await _patch_xsrf(c)
        out = await c.apply(
            vacancy_id="1",
            resume_id="abc",
            letter="",
            per_attempt_timeout=5.0,
        )
    assert out.status == ApplyStatus.TEMP_ERROR
    assert out.retryable
    assert out.http_status == 503
