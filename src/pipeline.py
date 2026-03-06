"""
Основной пайплайн бота.

run_morning_pipeline():
  1. Поиск вакансий по всем ключевым словам.
  2. Дедупликация + фильтрация exclude_keywords.
  3. Вакансии с has_test=True → REQUIRES_TEST, не предлагаем.
  4. Для остальных:
     a. Автоматический отклик (если HH credentials заданы).
     b. Отправка карточки в Telegram.
  5. Запись метрик в БД.

run_evening_summary():
  Отправляет статистику за день.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import structlog
from telegram import Bot
from telegram.constants import ParseMode
from telegram.ext import Application

from src import database as db
from src.bot.formatters import (
    format_apply_result,
    format_run_finished,
    format_run_started,
    format_summary,
    format_vacancy_card,
)
from src.bot.keyboards import vacancy_keyboard
from src.config import AppConfig, get_config
from src.hh.client import HHClient
from src.hh.schemas import VacancySchema
from src.models import VacancyStatus

log = structlog.get_logger(__name__)


async def run_morning_pipeline(app: Application) -> None:
    config = get_config()
    bot: Bot = app.bot
    chat_id = config.chat_id

    run_id = await db.create_run()
    log.info("pipeline.morning.start", run_id=run_id)

    counts = {
        "fetched": 0,
        "duplicate": 0,
        "excluded": 0,
        "requires_test": 0,
        "sent": 0,
        "apply_ok": 0,
        "apply_fail": 0,
        "errors": 0,
    }

    try:
        await bot.send_message(
            chat_id=chat_id,
            text=format_run_started(config.hh.search.include_keywords),
            parse_mode=ParseMode.HTML,
        )
    except Exception as exc:
        log.warning("pipeline.notify_start.error", error=str(exc))

    vacancies: list[VacancySchema] = []
    async with HHClient(
        user_agent=config.hh.user_agent,
        qps=config.hh.rate_limit.qps,
        burst=config.hh.rate_limit.burst,
    ) as hh:
        # Авторизация (нужна для отклика)
        logged_in = await hh.login(
            config.env.hh_username,
            config.env.hh_password.get_secret_value(),
        )

        # Поиск по каждому ключевому слову
        seen_ids: set[str] = set()
        for keyword in config.hh.search.include_keywords:
            try:
                fetched = await hh.search_all(
                    text=keyword,
                    area=config.hh.search.area,
                    schedule=config.hh.search.schedule or None,
                    employment=config.hh.search.employment or None,
                    period=max(1, config.hh.search.published_within_hours // 24),
                    max_vacancies=config.hh.search.max_vacancies_per_run,
                )
                for v in fetched:
                    if v.id not in seen_ids:
                        seen_ids.add(v.id)
                        vacancies.append(v)
                counts["fetched"] += len(fetched)
                log.info("pipeline.keyword.done", keyword=keyword, count=len(fetched))
            except Exception as exc:
                counts["errors"] += 1
                log.exception("pipeline.keyword.error", keyword=keyword, error=str(exc))

        # Ограничение на общее число
        vacancies = vacancies[: config.hh.search.max_vacancies_per_run]

        # Обработка каждой вакансии
        for vacancy in vacancies:
            try:
                await _process_vacancy(
                    vacancy=vacancy,
                    hh=hh,
                    bot=bot,
                    chat_id=chat_id,
                    config=config,
                    counts=counts,
                    logged_in=logged_in,
                )
                # Небольшая пауза между карточками чтобы не флудить
                await asyncio.sleep(0.5)
            except Exception as exc:
                counts["errors"] += 1
                log.exception("pipeline.vacancy.error", vacancy_id=vacancy.id, error=str(exc))

    # Запись итогов
    await db.finish_run(run_id, counts)
    await db.log_action("morning_run_finished", payload=counts)

    try:
        await bot.send_message(
            chat_id=chat_id,
            text=format_run_finished(counts["sent"], counts["requires_test"]),
            parse_mode=ParseMode.HTML,
        )
    except Exception as exc:
        log.warning("pipeline.notify_finish.error", error=str(exc))

    log.info("pipeline.morning.done", run_id=run_id, counts=counts)


async def _process_vacancy(
    *,
    vacancy: VacancySchema,
    hh: HHClient,
    bot: Bot,
    chat_id: int,
    config: AppConfig,
    counts: dict,
    logged_in: bool,
) -> None:
    """Обрабатывает одну вакансию: фильтрация → отклик → Telegram."""

    # Пропустить уже виденные
    if await db.vacancy_already_seen(vacancy.id, config.storage.retention_days):
        counts["duplicate"] += 1
        log.debug("pipeline.duplicate", vacancy_id=vacancy.id)
        return

    # Фильтр по exclude_keywords
    if vacancy.matches_exclude(config.hh.search.exclude_keywords):
        counts["excluded"] += 1
        log.debug("pipeline.excluded", vacancy_id=vacancy.id, title=vacancy.name)
        await db.upsert_vacancy(
            vacancy_id=vacancy.id,
            title=vacancy.name,
            employer=vacancy.employer.name,
            url=vacancy.vacancy_url,
            apply_url=vacancy.effective_apply_url,
            salary_text=vacancy.salary_text,
            status=VacancyStatus.SKIPPED,
        )
        return

    # Вакансии с тестом — не предлагаем отклик
    if vacancy.has_test:
        counts["requires_test"] += 1
        log.info("pipeline.requires_test", vacancy_id=vacancy.id, title=vacancy.name)
        await db.upsert_vacancy(
            vacancy_id=vacancy.id,
            title=vacancy.name,
            employer=vacancy.employer.name,
            url=vacancy.vacancy_url,
            apply_url=vacancy.effective_apply_url,
            salary_text=vacancy.salary_text,
            status=VacancyStatus.REQUIRES_TEST,
        )
        await db.log_action("requires_test", vacancy_id=vacancy.id)
        return

    # Сохранить вакансию как SENT
    await db.upsert_vacancy(
        vacancy_id=vacancy.id,
        title=vacancy.name,
        employer=vacancy.employer.name,
        url=vacancy.vacancy_url,
        apply_url=vacancy.effective_apply_url,
        salary_text=vacancy.salary_text,
        status=VacancyStatus.SENT,
    )

    # Автоматический отклик (если авторизованы и resume_id задан)
    apply_ok = False
    resume_id = config.env.hh_resume_id
    if logged_in and resume_id:
        letter = config.render_cover_letter(
            title=vacancy.name,
            employer=vacancy.employer.name,
        )
        apply_ok = await hh.apply_to_vacancy(
            vacancy_id=vacancy.id,
            resume_id=resume_id,
            letter=letter,
        )
        if apply_ok:
            counts["apply_ok"] += 1
            await db.set_vacancy_status(vacancy.id, VacancyStatus.APPLIED_CONFIRMED)
            await db.log_action(
                "auto_applied",
                vacancy_id=vacancy.id,
                payload={"title": vacancy.name, "employer": vacancy.employer.name},
            )
        else:
            counts["apply_fail"] += 1
            await db.log_action("auto_apply_failed", vacancy_id=vacancy.id)

    # Отправить карточку в Telegram
    card_text = format_vacancy_card(vacancy)

    # Если автоотклик успешен — добавляем уведомление
    if apply_ok:
        card_text += "\n\n" + format_apply_result(
            vacancy.name, vacancy.employer.name, success=True
        )

    msg = await bot.send_message(
        chat_id=chat_id,
        text=card_text,
        parse_mode=ParseMode.HTML,
        reply_markup=vacancy_keyboard(
            vacancy_id=vacancy.id,
            vacancy_url=vacancy.vacancy_url,
            apply_url=vacancy.effective_apply_url,
        ),
        disable_web_page_preview=True,
    )

    await db.set_vacancy_message_id(vacancy.id, msg.message_id)
    await db.log_action(
        "sent_to_telegram",
        vacancy_id=vacancy.id,
        payload={"message_id": msg.message_id, "auto_applied": apply_ok},
    )
    counts["sent"] += 1
    log.info(
        "pipeline.sent",
        vacancy_id=vacancy.id,
        title=vacancy.name,
        auto_applied=apply_ok,
    )


async def run_evening_summary(app: Application) -> None:
    """Отправляет вечерний отчёт."""
    config = get_config()
    stats = await db.get_today_stats()
    text = format_summary(stats)

    try:
        await app.bot.send_message(
            chat_id=config.chat_id,
            text=text,
            parse_mode=ParseMode.HTML,
            disable_web_page_preview=True,
        )
        log.info("pipeline.summary.sent")
    except Exception as exc:
        log.exception("pipeline.summary.error", error=str(exc))
