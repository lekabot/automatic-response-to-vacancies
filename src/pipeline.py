"""
Пайплайн поиска вакансий и автоматических откликов.

run_user_pipeline() запускается по кнопке «Запустить поиск» из бота.
Поддерживает отмену через asyncio.Event и дневной лимит откликов.
"""
from __future__ import annotations

import asyncio
from typing import TypedDict

import structlog
from telegram import Bot
from telegram.constants import ParseMode

from src import database as db
from src.config import get_config
from src.hh.client import HHClient
from src.hh.schemas import VacancySchema
from src.models import VacancyStatus

log = structlog.get_logger(__name__)


class PipelineResult(TypedDict):
    applied: int
    stopped_by_limit: bool


async def run_user_pipeline(
    *,
    chat_id: int,
    hh_email: str,
    hh_password: str | None,
    hhtoken: str | None = None,
    resume_id: str,
    keywords: list[str],
    cover_letter: str,
    cancel_event: asyncio.Event,
) -> PipelineResult:
    """
    Ищет вакансии и откликается на них.

    Args:
        chat_id: Telegram chat ID.
        hh_email: Email от аккаунта hh.ru.
        hh_password: Пароль (None для OTP-аккаунтов).
        hhtoken: Сессионный токен (используется вместо пароля для OTP-аккаунтов).
        resume_id: ID резюме для откликов.
        keywords: Ключевые слова для поиска.
        cover_letter: Шаблон письма (пустая строка — без письма).
        cancel_event: Установите для остановки пайплайна.
    """
    config = get_config()
    result: PipelineResult = {"applied": 0, "stopped_by_limit": False}

    async with HHClient(
        user_agent=config.hh.user_agent,
        qps=config.hh.rate_limit.qps,
        burst=config.hh.rate_limit.burst,
        hhtoken=hhtoken,
    ) as hh:
        if hhtoken:
            logged_in = True
        else:
            logged_in = await hh.login(hh_email, hh_password or "")
        if not logged_in:
            log.error("pipeline.login_failed", chat_id=chat_id)
            return result

        vacancies = await _collect_vacancies(hh, keywords, config)
        log.info("pipeline.collected", total=len(vacancies), chat_id=chat_id)

        for vacancy in vacancies:
            if cancel_event.is_set():
                break

            today_count = await db.get_applied_today_count()
            if today_count >= config.hh.search.daily_apply_limit:
                result["stopped_by_limit"] = True
                log.info("pipeline.daily_limit_reached", limit=config.hh.search.daily_apply_limit)
                break

            try:
                applied = await _process_vacancy(
                    vacancy=vacancy,
                    hh=hh,
                    cover_letter=cover_letter,
                    resume_id=resume_id,
                    config=config,
                )
            except Exception as exc:
                log.exception("pipeline.vacancy.error", vacancy_id=vacancy.id, error=str(exc))
                applied = False

            if applied:
                result["applied"] += 1

            await asyncio.sleep(0.5)

    return result


async def _collect_vacancies(hh: HHClient, keywords: list[str], config) -> list[VacancySchema]:
    """Собирает уникальные вакансии по всем ключевым словам, фильтруя по заголовку."""
    vacancies: list[VacancySchema] = []
    seen_ids: set[str] = set()

    for keyword in keywords:
        try:
            fetched = await hh.search_all(
                text=keyword,
                area=config.hh.search.area,
                schedule=config.hh.search.schedule or None,
                employment=config.hh.search.employment or None,
                search_field=config.hh.search.search_field or None,
                period=max(1, config.hh.search.published_within_hours // 24),
                max_vacancies=config.hh.search.max_vacancies_per_run,
            )
            added = 0
            for v in fetched:
                if v.id not in seen_ids and v.matches_keywords(keywords):
                    seen_ids.add(v.id)
                    vacancies.append(v)
                    added += 1
            log.info("pipeline.keyword.done", keyword=keyword, fetched=len(fetched), added=added)
        except Exception as exc:
            log.exception("pipeline.keyword.error", keyword=keyword, error=str(exc))

    return vacancies[: config.hh.search.max_vacancies_per_run]


async def _process_vacancy(
    *,
    vacancy: VacancySchema,
    hh: HHClient,
    cover_letter: str,
    resume_id: str,
    config,
) -> bool:
    """
    Обрабатывает одну вакансию: фильтрация → отклик → запись в БД.

    Returns:
        True если отклик успешно отправлен.
    """
    if await db.vacancy_already_seen(vacancy.id, config.storage.retention_days):
        return False

    if vacancy.matches_exclude(config.hh.search.exclude_keywords):
        await db.upsert_vacancy(
            vacancy_id=vacancy.id,
            title=vacancy.name,
            employer=vacancy.employer.name,
            url=vacancy.vacancy_url,
            salary_text=vacancy.salary_text,
            status=VacancyStatus.SKIPPED,
        )
        return False

    if vacancy.has_test:
        await db.upsert_vacancy(
            vacancy_id=vacancy.id,
            title=vacancy.name,
            employer=vacancy.employer.name,
            url=vacancy.vacancy_url,
            salary_text=vacancy.salary_text,
            status=VacancyStatus.REQUIRES_TEST,
        )
        return False

    letter = (
        cover_letter.replace("\\n", "\n").format(
            title=vacancy.name,
            employer=vacancy.employer.name,
        )
        if cover_letter
        else ""
    )

    applied = await hh.apply(vacancy_id=vacancy.id, resume_id=resume_id, letter=letter)

    await db.upsert_vacancy(
        vacancy_id=vacancy.id,
        title=vacancy.name,
        employer=vacancy.employer.name,
        url=vacancy.vacancy_url,
        salary_text=vacancy.salary_text,
        status=VacancyStatus.APPLIED if applied else VacancyStatus.APPLY_FAILED,
    )

    log.info("pipeline.vacancy", vacancy_id=vacancy.id, title=vacancy.name, applied=applied)
    return applied
