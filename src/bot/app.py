"""Фабрика Telegram Application."""
from __future__ import annotations

import pytz
import structlog
from telegram.ext import Application, JobQueue

from src.bot.handlers import register_handlers
from src.config import AppConfig

log = structlog.get_logger(__name__)


def build_application(config: AppConfig) -> Application:
    """Создаёт и настраивает Application с JobQueue."""
    app = (
        Application.builder()
        .token(config.bot_token)
        .arbitrary_callback_data(False)
        .build()
    )

    register_handlers(app)
    _schedule_jobs(app, config)

    log.info("bot.app.built")
    return app


def _schedule_jobs(app: Application, config: AppConfig) -> None:
    """Регистрирует утренний и вечерний джобы в JobQueue."""
    from src.pipeline import run_morning_pipeline, run_evening_summary

    tz = pytz.timezone(config.runtime.timezone)

    run_h, run_m = map(int, config.runtime.run_time.split(":"))
    summary_h, summary_m = map(int, config.runtime.summary_time.split(":"))

    import datetime

    # Утренний поиск
    app.job_queue.run_daily(
        callback=_morning_job,
        time=datetime.time(hour=run_h, minute=run_m, tzinfo=tz),
        name="morning_search",
    )

    # Вечерний отчёт
    app.job_queue.run_daily(
        callback=_summary_job,
        time=datetime.time(hour=summary_h, minute=summary_m, tzinfo=tz),
        name="evening_summary",
    )

    log.info(
        "jobs.scheduled",
        morning=config.runtime.run_time,
        summary=config.runtime.summary_time,
        tz=config.runtime.timezone,
    )


async def _morning_job(context) -> None:  # type: ignore[no-untyped-def]
    from src.pipeline import run_morning_pipeline

    try:
        await run_morning_pipeline(context.application)
    except Exception as exc:
        log.exception("job.morning.error", error=str(exc))


async def _summary_job(context) -> None:  # type: ignore[no-untyped-def]
    from src.pipeline import run_evening_summary

    try:
        await run_evening_summary(context.application)
    except Exception as exc:
        log.exception("job.summary.error", error=str(exc))
