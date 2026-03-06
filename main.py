#!/usr/bin/env python3
"""
Точка входа: HH Vacancy Assistant Telegram Bot.

Запуск:
    python main.py

или через Docker:
    docker compose up
"""
from __future__ import annotations

import asyncio
import logging
import os
import sys
from pathlib import Path

import structlog

# Убеждаемся, что src/ доступен при запуске из корня проекта
sys.path.insert(0, str(Path(__file__).parent))


def _setup_logging() -> None:
    """Настраивает structlog + стандартный logging."""
    log_level = os.getenv("LOG_LEVEL", "INFO").upper()

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer()
            if sys.stdout.isatty()
            else structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, log_level, logging.INFO)
        ),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
    )

    logging.basicConfig(
        level=getattr(logging, log_level, logging.INFO),
        format="%(message)s",
    )
    # Убираем лишний шум от httpx / httpcore
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("telegram").setLevel(logging.WARNING)


async def _run() -> None:
    import structlog

    log = structlog.get_logger(__name__)

    from src.config import get_config
    from src import database as db
    from src.bot.app import build_application

    # Загружаем конфиг
    config = get_config()

    # Создаём директорию для данных если не существует
    db_path = Path(config.storage.sqlite_path)
    db_path.parent.mkdir(parents=True, exist_ok=True)

    # Инициализируем БД
    db.init_db(config.db_url)

    # Применяем миграции Alembic автоматически при старте
    _run_migrations(config.storage.sqlite_path)

    log.info(
        "bot.starting",
        chat_id=config.chat_id,
        run_time=config.runtime.run_time,
        summary_time=config.runtime.summary_time,
        timezone=config.runtime.timezone,
    )

    # Строим и запускаем бота
    app = build_application(config)

    async with app:
        await app.start()
        log.info("bot.polling.started")
        await app.updater.start_polling(drop_pending_updates=True)

        # Блокируемся до получения SIGINT / SIGTERM
        try:
            await asyncio.Event().wait()
        except (KeyboardInterrupt, SystemExit):
            pass
        finally:
            log.info("bot.stopping")
            await app.updater.stop()
            await app.stop()


def _run_migrations(sqlite_path: str) -> None:
    """Запускает алембик-миграции синхронно при старте."""
    try:
        from alembic.config import Config
        from alembic import command

        alembic_cfg = Config("alembic.ini")
        alembic_cfg.set_main_option(
            "sqlalchemy.url", f"sqlite+aiosqlite:///{sqlite_path}"
        )
        command.upgrade(alembic_cfg, "head")
    except Exception as exc:
        import structlog
        structlog.get_logger(__name__).warning(
            "migrations.skipped", error=str(exc)
        )


if __name__ == "__main__":
    _setup_logging()
    asyncio.run(_run())
