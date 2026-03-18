"""validate_session_status: INVALID vs TEMP_UNAVAILABLE."""
from __future__ import annotations

import httpx
import pytest
import respx

from src.hh.client import HHClient
from src.hh.session_status import SessionValidationStatus


@pytest.mark.asyncio
@respx.mock
async def test_validate_session_503_temp() -> None:
    respx.get("https://hh.ru/applicant/resumes").mock(
        return_value=httpx.Response(503, text="bad gateway")
    )
    async with HHClient(user_agent="t", hhtoken="x", qps=100, burst=100) as c:
        st = await c.validate_session_status()
    assert st is SessionValidationStatus.TEMP_UNAVAILABLE


@pytest.mark.asyncio
@respx.mock
async def test_validate_session_401_invalid() -> None:
    respx.get("https://hh.ru/applicant/resumes").mock(
        return_value=httpx.Response(401, text="unauthorized")
    )
    async with HHClient(user_agent="t", hhtoken="x", qps=100, burst=100) as c:
        st = await c.validate_session_status()
    assert st is SessionValidationStatus.INVALID


@pytest.mark.asyncio
@respx.mock
async def test_validate_session_200_applicant_valid() -> None:
    respx.get("https://hh.ru/applicant/resumes").mock(
        return_value=httpx.Response(
            200,
            text='<html><a href="/resume/abcdefghij">CV</a></html>',
        )
    )
    async with HHClient(user_agent="t", hhtoken="tok", qps=100, burst=100) as c:
        st = await c.validate_session_status()
    assert st is SessionValidationStatus.VALID


@pytest.mark.asyncio
@respx.mock
async def test_validate_session_challenge_page_temp() -> None:
    respx.get("https://hh.ru/applicant/resumes").mock(
        return_value=httpx.Response(
            200,
            text="<html>smartcaptcha verify</html>",
        )
    )
    async with HHClient(user_agent="t", hhtoken="tok", qps=100, burst=100) as c:
        st = await c.validate_session_status()
    assert st is SessionValidationStatus.TEMP_UNAVAILABLE


@pytest.mark.asyncio
@respx.mock
async def test_validate_session_connect_timeout_temp() -> None:
    respx.get("https://hh.ru/applicant/resumes").mock(
        side_effect=httpx.ConnectTimeout("timed out")
    )
    async with HHClient(user_agent="t", hhtoken="tok", qps=100, burst=100) as c:
        st = await c.validate_session_status()
    assert st is SessionValidationStatus.TEMP_UNAVAILABLE
