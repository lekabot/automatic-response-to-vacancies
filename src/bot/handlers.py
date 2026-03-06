"""
Telegram bot handlers.

Команды:
  /start   — приветствие + chat_id
  /whoami  — вывести chat_id (для первоначальной настройки)
  /status  — статус последнего запуска
  /run     — запустить поиск вручную прямо сейчас
  /summary — получить вечерний отчёт прямо сейчас

Callbacks:
  applied:<vacancy_id>  — пользователь подтвердил отклик
  skip:<vacancy_id>     — пользователь пропустил вакансию
"""
from __future__ import annotations

import structlog
from telegram import Update
from telegram.constants import ParseMode
from telegram.ext import Application, CallbackQueryHandler, CommandHandler, ContextTypes

from src import database as db
from src.bot.formatters import format_summary
from src.models import VacancyStatus

log = structlog.get_logger(__name__)


# ---------------------------------------------------------------------------
# Command handlers
# ---------------------------------------------------------------------------


async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not update.effective_chat:
        return
    chat_id = update.effective_chat.id
    text = (
        "👋 <b>HH Vacancy Assistant</b>\n\n"
        f"Ваш <b>chat_id</b>: <code>{chat_id}</code>\n\n"
        "Скопируйте это значение в <code>config.yaml → telegram.chat_id</code>.\n\n"
        "Команды:\n"
        "  /whoami — показать chat_id\n"
        "  /run    — запустить поиск прямо сейчас\n"
        "  /status — статус последнего запуска\n"
        "  /summary — отчёт за сегодня"
    )
    await update.message.reply_text(text, parse_mode=ParseMode.HTML)


async def cmd_whoami(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not update.effective_chat:
        return
    chat_id = update.effective_chat.id
    await update.message.reply_text(
        f"Ваш <b>chat_id</b>: <code>{chat_id}</code>",
        parse_mode=ParseMode.HTML,
    )


async def cmd_summary(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    stats = await db.get_today_stats()
    text = format_summary(stats)
    await update.message.reply_text(text, parse_mode=ParseMode.HTML, disable_web_page_preview=True)


async def cmd_run(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """Запускает утренний пайплайн немедленно (для ручного тестирования)."""
    # Импорт здесь чтобы избежать circular import
    from src.pipeline import run_morning_pipeline

    await update.message.reply_text("🔍 Запускаю поиск вакансий...")
    try:
        await run_morning_pipeline(context.application)
    except Exception as exc:
        log.exception("cmd_run.error", error=str(exc))
        await update.message.reply_text(f"❌ Ошибка: {exc}")


async def cmd_status(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    stats = await db.get_today_stats()
    counts = stats["counts"]
    text = (
        "📈 <b>Статус за сегодня</b>\n\n"
        f"Предложено: {counts.get('sent', 0)}\n"
        f"Откликнулся: {counts.get('applied_confirmed', 0)}\n"
        f"Пропущено: {counts.get('skipped', 0)}\n"
        f"С тестом: {counts.get('requires_test', 0)}"
    )
    await update.message.reply_text(text, parse_mode=ParseMode.HTML)


# ---------------------------------------------------------------------------
# Callback handlers
# ---------------------------------------------------------------------------


async def callback_applied(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """Обработчик кнопки «Подтвердить отклик»."""
    query = update.callback_query
    await query.answer("✅ Отклик засчитан!")

    vacancy_id = query.data.split(":", 1)[1]
    await db.set_vacancy_status(vacancy_id, VacancyStatus.APPLIED_CONFIRMED)
    await db.log_action("applied_confirmed", vacancy_id=vacancy_id)

    # Редактируем кнопки — убираем callback-кнопки, оставляем только URL
    try:
        from telegram import InlineKeyboardMarkup

        # Получаем текущую клавиатуру и оставляем только первую строку (URL-кнопки)
        current_markup = query.message.reply_markup
        url_row = current_markup.inline_keyboard[0] if current_markup else []
        new_markup = InlineKeyboardMarkup([url_row]) if url_row else None

        await query.edit_message_reply_markup(reply_markup=new_markup)
        await query.message.reply_text(
            "✅ <b>Отклик подтверждён!</b>",
            parse_mode=ParseMode.HTML,
        )
    except Exception as exc:
        log.warning("callback_applied.edit_error", error=str(exc))

    log.info("vacancy.applied_confirmed", vacancy_id=vacancy_id)


async def callback_skip(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """Обработчик кнопки «Пропустить»."""
    query = update.callback_query
    await query.answer("⏭ Вакансия пропущена")

    vacancy_id = query.data.split(":", 1)[1]
    await db.set_vacancy_status(vacancy_id, VacancyStatus.SKIPPED)
    await db.log_action("skipped", vacancy_id=vacancy_id)

    # Убираем callback-кнопки
    try:
        current_markup = query.message.reply_markup
        url_row = current_markup.inline_keyboard[0] if current_markup else []
        new_markup = InlineKeyboardMarkup([url_row]) if url_row else None
        await query.edit_message_reply_markup(reply_markup=new_markup)
    except Exception as exc:
        log.warning("callback_skip.edit_error", error=str(exc))

    log.info("vacancy.skipped", vacancy_id=vacancy_id)


# ---------------------------------------------------------------------------
# Registration
# ---------------------------------------------------------------------------


def register_handlers(app: Application) -> None:
    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("whoami", cmd_whoami))
    app.add_handler(CommandHandler("run", cmd_run))
    app.add_handler(CommandHandler("status", cmd_status))
    app.add_handler(CommandHandler("summary", cmd_summary))
    app.add_handler(CallbackQueryHandler(callback_applied, pattern=r"^applied:"))
    app.add_handler(CallbackQueryHandler(callback_skip, pattern=r"^skip:"))
