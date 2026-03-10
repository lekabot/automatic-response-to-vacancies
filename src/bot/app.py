"""Фабрика Telegram Application."""
from __future__ import annotations

import structlog
from telegram.ext import Application

from src.bot.handlers import register_handlers
from src.config import AppConfig

log = structlog.get_logger(__name__)


def build_application(config: AppConfig) -> Application:
    """Создаёт и настраивает Application."""
    app = (
        Application.builder()
        .token(config.bot_token)
        .arbitrary_callback_data(False)
        .build()
    )

    register_handlers(app)

    log.info("bot.app.built")
    return app
