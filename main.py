#!/usr/bin/env python3
"""HH Vacancy Assistant — точка входа.

    python main.py
    docker compose up
"""
from __future__ import annotations

import asyncio
import logging
import os
import sys
from pathlib import Path

import structlog

sys.path.insert(0, str(Path(__file__).parent))


def _setup_logging() -> None:
    level = getattr(logging, os.getenv("LOG_LEVEL", "INFO").upper(), logging.INFO)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer() if sys.stdout.isatty() else structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
    )
    logging.basicConfig(level=level, format="%(message)s")
    for noisy in ("httpx", "httpcore", "telegram"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


async def _run() -> None:
    log = structlog.get_logger(__name__)

    from src.config import get_config
    from src import database as db
    from src.bot.app import build_application

    config = get_config()

    Path(config.storage.sqlite_path).parent.mkdir(parents=True, exist_ok=True)
    db.init_db(config.db_url)
    _run_migrations(config.storage.sqlite_path)

    log.info("bot.starting")
    app = build_application(config)

    async with app:
        await app.start()
        await app.updater.start_polling(drop_pending_updates=True)
        log.info("bot.polling.started")
        try:
            await asyncio.Event().wait()
        except (KeyboardInterrupt, SystemExit):
            pass
        finally:
            await app.updater.stop()
            await app.stop()


def _run_migrations(sqlite_path: str) -> None:
    try:
        from alembic.config import Config
        from alembic import command

        cfg = Config("alembic.ini")
        cfg.set_main_option("sqlalchemy.url", f"sqlite+aiosqlite:///{sqlite_path}")
        command.upgrade(cfg, "head")
    except Exception as exc:
        structlog.get_logger(__name__).warning("migrations.skipped", error=str(exc))


if __name__ == "__main__":
    _setup_logging()
    asyncio.run(_run())
