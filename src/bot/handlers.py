"""
Telegram bot handlers.

Сценарий:
  /start → мастер настройки (3 шага: ключевые слова → письмо → аккаунт hh.ru)
         → главное меню (3 кнопки редактирования + «▶️ Запустить поиск»)
         → поиск в фоне + почасовые отчёты
         → кнопка «⏹ Остановить»

States:
  SETUP_KEYWORDS / SETUP_COVER_LETTER / SETUP_EMAIL / SETUP_OTP /
  SELECT_RESUME / MAIN_MENU / EDIT_KEYWORDS / EDIT_COVER_LETTER /
  EDIT_CREDENTIALS / EDIT_OTP / SEARCHING
"""
from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from typing import Any

import structlog
from telegram import Update
from telegram.constants import ParseMode
from telegram.ext import (
    Application,
    CallbackQueryHandler,
    CommandHandler,
    ContextTypes,
    ConversationHandler,
    MessageHandler,
    filters,
)
from src import database as db
from src.bot.formatters import format_final_summary
from src.bot.keyboards import (
    back_keyboard,
    main_menu_keyboard,
    resume_keyboard,
    skip_letter_keyboard,
    stop_keyboard,
)
from src.config import get_config
from src.hh.client import HHClient
from src.models import UserSettings
from src.pipeline import validate_cover_letter_braces

log = structlog.get_logger(__name__)

_BG_SHUTDOWN_GRACEFUL_SEC = 15.0
_BG_SHUTDOWN_HARD_SEC = 8.0

(
    SETUP_KEYWORDS,
    SETUP_COVER_LETTER,
    SETUP_EMAIL,
    SETUP_PASSWORD,
    SETUP_OTP,
    SELECT_RESUME,
    MAIN_MENU,
    EDIT_KEYWORDS,
    EDIT_COVER_LETTER,
    EDIT_CREDENTIALS,
    EDIT_PASSWORD,
    EDIT_OTP,
    SEARCHING,
) = range(13)

# ---------------------------------------------------------------------------
# Prompt texts
# ---------------------------------------------------------------------------

_PROMPT_KEYWORDS = (
    "🔍 <b>Шаг 1 из 3 — Ключевые слова</b>\n\n"
    "Введите ключевые слова для поиска вакансий через запятую.\n\n"
    "<b>Пример:</b>\n"
    "<code>Python разработчик, Python backend, Senior Python developer</code>"
)

_PROMPT_LETTER = (
    "✉️ <b>Шаг 2 из 3 — Сопроводительное письмо</b>\n\n"
    "Введите текст письма. Поддерживаются переменные:\n"
    "<code>{title}</code> — название вакансии\n"
    "<code>{employer}</code> — название компании\n\n"
    "<b>Пример:</b>\n"
    "<i>Добрый день! Меня заинтересовала вакансия «{title}» в {employer}. "
    "Python-разработчик, 5+ лет опыта. Готов обсудить.\n\nС уважением, Иван.</i>\n\n"
    "Введите текст или нажмите кнопку, чтобы откликаться без письма:"
)

_PROMPT_EMAIL = (
    "🔑 <b>Шаг 3 из 3 — Аккаунт hh.ru</b>\n\n"
    "Введите email от вашего аккаунта соискателя на hh.ru:"
)

_PROMPT_OTP = (
    "📧 <b>Код подтверждения</b>\n\n"
    "На ваш email отправлен 4-значный код.\n"
    "Введите его ниже:"
)

_PROMPT_PASSWORD = (
    "🔐 <b>Пароль от hh.ru</b>\n\n"
    "Для этого аккаунта вход по паролю. Введите пароль (сообщение не сохраняется в истории чата дольше сессии):"
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _settings_text(s: UserSettings) -> str:
    kws = s.keywords
    kw_str = ", ".join(kws[:5]) + (f" (+{len(kws) - 5})" if len(kws) > 5 else "")
    letter = "Да" if s.cover_letter else "Нет"
    account = _esc(s.hh_email or "—")
    resume = _esc(s.resume_title or s.resume_id or "—")
    return (
        "⚙️ <b>Настройки поиска</b>\n\n"
        f"🔍 <b>Ключевые слова:</b> {_esc(kw_str)}\n"
        f"✉️ <b>Письмо:</b> {letter}\n"
        f"👤 <b>Аккаунт hh.ru:</b> {account}\n"
        f"📄 <b>Резюме:</b> {resume}\n\n"
        "Измените настройки или запустите поиск:"
    )


def _esc(t: str) -> str:
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


async def _do_initiate_login(email: str) -> dict:
    """Запускает шаг 1 авторизации hh.ru; возвращает dict с method + cookies."""
    config = get_config()
    async with HHClient(user_agent=config.hh.user_agent) as hh:
        return await hh.initiate_login(email)


async def _complete_otp_and_resumes(
    email: str, code: str, login_info: dict
) -> tuple[bool, str | None, list[dict]]:
    """Проверяет OTP-код и возвращает (ok, hhtoken, resumes)."""
    config = get_config()
    async with HHClient(user_agent=config.hh.user_agent) as hh:
        hh.restore_cookies(login_info.get("cookies", {}))
        ok = await hh.complete_otp_login(email, code)
        if not ok:
            return False, None, []
        hhtoken = hh.get_hhtoken()
        resumes = await hh.get_resumes()
    return True, hhtoken, resumes


async def _complete_password_and_resumes(
    email: str, password: str, login_info: dict
) -> tuple[bool, str | None, list[dict]]:
    config = get_config()
    async with HHClient(user_agent=config.hh.user_agent) as hh:
        ok = await hh.complete_password_login(email, password, login_info)
        if not ok:
            return False, None, []
        hhtoken = hh.get_hhtoken()
        resumes = await hh.get_resumes()
    return True, hhtoken, resumes


async def _resumes_with_hhtoken(hhtoken: str) -> list[dict]:
    """Получает список резюме по сохранённому hhtoken."""
    config = get_config()
    async with HHClient(user_agent=config.hh.user_agent, hhtoken=hhtoken) as hh:
        return await hh.get_resumes()


# ---------------------------------------------------------------------------
# /start
# ---------------------------------------------------------------------------


async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    chat_id = update.effective_chat.id
    settings = await db.get_user_settings(chat_id)

    if settings and settings.is_complete():
        msg = update.message or update.callback_query.message
        await msg.reply_text(
            _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
        )
        return MAIN_MENU

    await update.message.reply_text(
        "👋 <b>HH Vacancy Assistant</b>\n\n"
        "Автоматические отклики на вакансии hh.ru. Настроим за 3 шага.\n\n"
        + _PROMPT_KEYWORDS,
        parse_mode=ParseMode.HTML,
    )
    return SETUP_KEYWORDS


# ---------------------------------------------------------------------------
# Шаг 1 — ключевые слова
# ---------------------------------------------------------------------------


async def setup_keywords(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    chat_id = update.effective_chat.id
    keywords = [k.strip() for k in update.message.text.replace("\n", ",").split(",") if k.strip()]
    if not keywords:
        await update.message.reply_text("⚠️ Введите хотя бы одно ключевое слово.")
        return SETUP_KEYWORDS

    await db.save_user_settings(chat_id, keywords_json=json.dumps(keywords, ensure_ascii=False))
    await update.message.reply_text(
        f"✅ Сохранено {len(keywords)} ключевых слов.\n\n" + _PROMPT_LETTER,
        parse_mode=ParseMode.HTML,
        reply_markup=skip_letter_keyboard(),
    )
    return SETUP_COVER_LETTER


# ---------------------------------------------------------------------------
# Шаг 2 — сопроводительное письмо
# ---------------------------------------------------------------------------


async def setup_letter_text(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    raw = update.message.text.strip()
    ok, err = validate_cover_letter_braces(raw)
    if not ok:
        await update.message.reply_text(f"⚠️ {err}\n\nИсправьте шаблон или используйте только {{title}} и {{employer}}.")
        return SETUP_COVER_LETTER
    await db.save_user_settings(update.effective_chat.id, cover_letter=raw)
    await update.message.reply_text(
        "✅ Письмо сохранено.\n\n" + _PROMPT_EMAIL, parse_mode=ParseMode.HTML
    )
    return SETUP_EMAIL


async def setup_letter_skip(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    await update.callback_query.answer()
    await db.save_user_settings(update.effective_chat.id, cover_letter=None)
    await update.callback_query.edit_message_text(
        "✅ Отклики без письма.\n\n" + _PROMPT_EMAIL, parse_mode=ParseMode.HTML
    )
    return SETUP_EMAIL


# ---------------------------------------------------------------------------
# Шаг 3 — email + пароль (два под-шага)
# ---------------------------------------------------------------------------


async def setup_email(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    email = update.message.text.strip()
    if "@" not in email:
        await update.message.reply_text("⚠️ Похоже, это не email. Введите корректный адрес.")
        return SETUP_EMAIL

    wait = await update.message.reply_text("⏳ Проверяю аккаунт...")
    login_info = await _do_initiate_login(email)

    if login_info["method"] == "error":
        await wait.edit_text(
            f"❌ Ошибка: {login_info['message']}\n\n" + _PROMPT_EMAIL,
            parse_mode=ParseMode.HTML,
        )
        return SETUP_EMAIL

    context.user_data["pending_email"] = email
    context.user_data["login_info"] = login_info

    if login_info.get("method") == "password":
        await wait.edit_text(_PROMPT_PASSWORD, parse_mode=ParseMode.HTML)
        return SETUP_PASSWORD

    otp_msg = (
        "📧 <b>Код уже отправлен</b>\n\n"
        "На ваш email уже был отправлен 4-значный код — проверьте входящие (и папку «Спам»).\n"
        "Введите его ниже:"
    ) if login_info.get("already_sent") else _PROMPT_OTP
    await wait.edit_text(otp_msg, parse_mode=ParseMode.HTML)
    return SETUP_OTP


async def setup_password(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    chat_id = update.effective_chat.id
    email = context.user_data.get("pending_email", "")
    login_info = context.user_data.get("login_info", {})
    password = update.message.text.strip()
    if len(password) < 1:
        await update.message.reply_text("⚠️ Введите пароль.")
        return SETUP_PASSWORD

    wait = await update.message.reply_text("⏳ Выполняю вход...")
    ok, hhtoken, resumes = await _complete_password_and_resumes(email, password, login_info)

    if not ok:
        await wait.edit_text(
            "❌ Неверный пароль или ошибка входа. Попробуйте снова или /start.\n\n" + _PROMPT_PASSWORD,
            parse_mode=ParseMode.HTML,
        )
        return SETUP_PASSWORD

    if not resumes:
        await wait.edit_text("❌ Нет резюме на аккаунте. Создайте резюме на hh.ru и введите /start.")
        return SETUP_EMAIL

    context.user_data.pop("pending_email", None)
    context.user_data.pop("login_info", None)
    await db.save_user_settings(chat_id, hh_email=email, hh_password=None, hhtoken=hhtoken)
    return await _finish_auth(update, context, chat_id, resumes, wait)


async def setup_otp(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    """Шаг ввода OTP-кода при настройке."""
    chat_id = update.effective_chat.id
    email = context.user_data.get("pending_email", "")
    login_info = context.user_data.get("login_info", {})
    code = update.message.text.strip()

    wait = await update.message.reply_text("⏳ Проверяю код...")
    ok, hhtoken, resumes = await _complete_otp_and_resumes(email, code, login_info)

    if not ok:
        await wait.edit_text("❌ Неверный код. Попробуйте снова или введите /start для перезапуска.\n\n" + _PROMPT_OTP, parse_mode=ParseMode.HTML)
        return SETUP_OTP

    if not resumes:
        await wait.edit_text("❌ Нет резюме на аккаунте. Создайте резюме на hh.ru и введите /start.")
        return SETUP_EMAIL

    context.user_data.pop("pending_email", None)
    context.user_data.pop("login_info", None)
    await db.save_user_settings(chat_id, hh_email=email, hh_password=None, hhtoken=hhtoken)
    return await _finish_auth(update, context, chat_id, resumes, wait)



async def _finish_auth(update, context, chat_id: int, resumes: list[dict], wait_msg) -> int:
    """Общий финал после успешной аутентификации: сохранить резюме и перейти в меню."""
    if len(resumes) == 1:
        r = resumes[0]
        await db.save_user_settings(chat_id, resume_id=r["id"], resume_title=r.get("title", ""))
        settings = await db.get_user_settings(chat_id)
        await wait_msg.edit_text(
            f"✅ Вход выполнен · Резюме: <b>{_esc(r.get('title', ''))}</b>",
            parse_mode=ParseMode.HTML,
        )
        await update.message.reply_text(
            _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
        )
        return MAIN_MENU

    await wait_msg.edit_text(
        f"✅ Вход выполнен · Найдено {len(resumes)} резюме. Выберите одно:",
        parse_mode=ParseMode.HTML,
        reply_markup=resume_keyboard(resumes),
    )
    return SELECT_RESUME


# ---------------------------------------------------------------------------
# Выбор резюме
# ---------------------------------------------------------------------------


async def select_resume(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    query = update.callback_query
    await query.answer()
    chat_id = update.effective_chat.id
    resume_id = query.data.split(":", 1)[1]

    settings_now = await db.get_user_settings(chat_id)
    resume_title = resume_id
    if settings_now and settings_now.hhtoken:
        try:
            for r in await _resumes_with_hhtoken(settings_now.hhtoken):
                if r.get("id") == resume_id:
                    resume_title = r.get("title", resume_id)
                    break
        except Exception:
            pass

    await db.save_user_settings(chat_id, resume_id=resume_id, resume_title=resume_title)
    settings = await db.get_user_settings(chat_id)
    await query.edit_message_text(
        f"✅ Резюме: <b>{_esc(resume_title)}</b>", parse_mode=ParseMode.HTML
    )
    await query.message.reply_text(
        _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
    )
    return MAIN_MENU


# ---------------------------------------------------------------------------
# Главное меню
# ---------------------------------------------------------------------------


async def main_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    query = update.callback_query
    await query.answer()

    if query.data == "edit_keywords":
        await query.edit_message_text("✏️ Введите новые ключевые слова через запятую:")
        return EDIT_KEYWORDS

    if query.data == "edit_letter":
        await query.edit_message_text(
            "✏️ Введите новый текст письма или нажмите кнопку:",
            reply_markup=skip_letter_keyboard(),
        )
        return EDIT_COVER_LETTER

    if query.data == "edit_credentials":
        await query.edit_message_text(
            "✏️ Введите новый email от аккаунта hh.ru:", parse_mode=ParseMode.HTML
        )
        context.user_data["editing_credentials"] = True
        return EDIT_CREDENTIALS

    if query.data == "start_search":
        return await _start_search(update, context)

    if query.data == "reset_applications":
        count = await db.reset_applied_vacancies(update.effective_chat.id)
        settings = await db.get_user_settings(update.effective_chat.id)
        await query.answer(f"Удалено {count} записей")
        await query.edit_message_text(
            f"🗑 <b>История откликов очищена</b> ({count} записей удалено)\n\n"
            + _settings_text(settings),
            parse_mode=ParseMode.HTML,
            reply_markup=main_menu_keyboard(),
        )
        return MAIN_MENU

    return MAIN_MENU


# ---------------------------------------------------------------------------
# Edit states
# ---------------------------------------------------------------------------


async def edit_keywords(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    chat_id = update.effective_chat.id
    keywords = [k.strip() for k in update.message.text.replace("\n", ",").split(",") if k.strip()]
    if not keywords:
        await update.message.reply_text("⚠️ Введите хотя бы одно ключевое слово.")
        return EDIT_KEYWORDS
    await db.save_user_settings(chat_id, keywords_json=json.dumps(keywords, ensure_ascii=False))
    settings = await db.get_user_settings(chat_id)
    await update.message.reply_text(
        _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
    )
    return MAIN_MENU


async def edit_letter_text(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    raw = update.message.text.strip()
    ok, err = validate_cover_letter_braces(raw)
    if not ok:
        await update.message.reply_text(f"⚠️ {err}")
        return EDIT_COVER_LETTER
    await db.save_user_settings(update.effective_chat.id, cover_letter=raw)
    settings = await db.get_user_settings(update.effective_chat.id)
    await update.message.reply_text(
        _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
    )
    return MAIN_MENU


async def edit_letter_skip(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    await update.callback_query.answer()
    await db.save_user_settings(update.effective_chat.id, cover_letter=None)
    settings = await db.get_user_settings(update.effective_chat.id)
    await update.callback_query.edit_message_text(
        _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
    )
    return MAIN_MENU


async def edit_credentials_email(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    """Первый шаг редактирования — email: инициируем авторизацию."""
    email = update.message.text.strip()
    if "@" not in email:
        await update.message.reply_text("⚠️ Введите корректный email.")
        return EDIT_CREDENTIALS

    wait = await update.message.reply_text("⏳ Проверяю аккаунт...")
    login_info = await _do_initiate_login(email)

    if login_info["method"] == "error":
        await wait.edit_text(
            f"❌ Ошибка: {login_info['message']}\n\nВведите email снова:",
            parse_mode=ParseMode.HTML,
        )
        return EDIT_CREDENTIALS

    context.user_data["pending_email"] = email
    context.user_data["login_info"] = login_info

    if login_info.get("method") == "password":
        await wait.edit_text(_PROMPT_PASSWORD, parse_mode=ParseMode.HTML)
        return EDIT_PASSWORD

    otp_msg = (
        "📧 <b>Код уже отправлен</b>\n\n"
        "На ваш email уже был отправлен 4-значный код — проверьте входящие (и папку «Спам»).\n"
        "Введите его ниже:"
    ) if login_info.get("already_sent") else _PROMPT_OTP
    await wait.edit_text(otp_msg, parse_mode=ParseMode.HTML)
    return EDIT_OTP


async def edit_password(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    chat_id = update.effective_chat.id
    email = context.user_data.get("pending_email", "")
    login_info = context.user_data.get("login_info", {})
    password = update.message.text.strip()
    if len(password) < 1:
        await update.message.reply_text("⚠️ Введите пароль.")
        return EDIT_PASSWORD

    wait = await update.message.reply_text("⏳ Выполняю вход...")
    ok, hhtoken, resumes = await _complete_password_and_resumes(email, password, login_info)

    if not ok:
        await wait.edit_text(
            "❌ Неверный пароль. Попробуйте снова:\n\n" + _PROMPT_PASSWORD,
            parse_mode=ParseMode.HTML,
        )
        return EDIT_PASSWORD

    context.user_data.pop("pending_email", None)
    context.user_data.pop("login_info", None)
    await db.save_user_settings(chat_id, hh_email=email, hh_password=None, hhtoken=hhtoken)
    if resumes:
        r = resumes[0]
        await db.save_user_settings(chat_id, resume_id=r["id"], resume_title=r.get("title", ""))

    settings = await db.get_user_settings(chat_id)
    await wait.edit_text("✅ Учётные данные обновлены.")
    await update.message.reply_text(
        _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
    )
    return MAIN_MENU


async def edit_otp(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    """Шаг ввода OTP-кода при редактировании учётных данных."""
    chat_id = update.effective_chat.id
    email = context.user_data.get("pending_email", "")
    login_info = context.user_data.get("login_info", {})
    code = update.message.text.strip()

    wait = await update.message.reply_text("⏳ Проверяю код...")
    ok, hhtoken, resumes = await _complete_otp_and_resumes(email, code, login_info)

    if not ok:
        await wait.edit_text(
            "❌ Неверный код. Попробуйте снова:\n\n" + _PROMPT_OTP,
            parse_mode=ParseMode.HTML,
        )
        return EDIT_OTP

    context.user_data.pop("pending_email", None)
    context.user_data.pop("login_info", None)
    await db.save_user_settings(chat_id, hh_email=email, hh_password=None, hhtoken=hhtoken)
    if resumes:
        r = resumes[0]
        await db.save_user_settings(chat_id, resume_id=r["id"], resume_title=r.get("title", ""))

    settings = await db.get_user_settings(chat_id)
    await wait.edit_text("✅ Учётные данные обновлены.")
    await update.message.reply_text(
        _settings_text(settings), parse_mode=ParseMode.HTML, reply_markup=main_menu_keyboard()
    )
    return MAIN_MENU



# ---------------------------------------------------------------------------
# Запуск / остановка поиска
# ---------------------------------------------------------------------------


def _search_task_done_callback(chat_id: int, user_data: dict) -> Any:
    """Возвращает callback для asyncio.Task: лог + очистка search_task / cancel_event."""

    def _on_done(t: asyncio.Task) -> None:
        if user_data.get("search_task") is not t:
            return
        try:
            if t.cancelled():
                log.info("search_task.finished", chat_id=chat_id, cancelled=True)
            else:
                exc = t.exception()
                if exc is not None:
                    log.error(
                        "search_task.finished",
                        chat_id=chat_id,
                        error=str(exc),
                        exc_info=exc,
                    )
                else:
                    log.info("search_task.finished", chat_id=chat_id, ok=True)
        finally:
            user_data.pop("search_task", None)
            user_data.pop("cancel_event", None)

    return _on_done


async def _start_search(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    chat_id = update.effective_chat.id
    settings = await db.get_user_settings(chat_id)
    config = get_config()

    if not settings or not settings.is_complete():
        await update.callback_query.answer("⚠️ Настройки неполные.", show_alert=True)
        return MAIN_MENU

    # Защита от двойного запуска
    existing_task: asyncio.Task | None = context.user_data.get("search_task")
    if existing_task and not existing_task.done():
        await update.callback_query.answer("⚠️ Поиск уже запущен!", show_alert=True)
        return SEARCHING

    await db.start_search_session(chat_id, datetime.now(timezone.utc))

    cancel_event = asyncio.Event()
    context.user_data["cancel_event"] = cancel_event

    kw_list = "\n".join(f"• {_esc(k)}" for k in settings.keywords[:10])
    msg = await update.callback_query.edit_message_text(
        f"🚀 <b>Начали откликаться за вас!</b>\n\nПоиск по словам:\n{kw_list}\n\n"
        "Перед каждым новым циклом подтягиваются актуальные настройки из БД.\n"
        "Каждый час присылаю статистику.",
        parse_mode=ParseMode.HTML,
        reply_markup=stop_keyboard(),
    )
    status_msg_id = (
        msg.message_id if hasattr(msg, "message_id") else update.callback_query.message.message_id
    )

    ud = context.user_data
    task = asyncio.create_task(
        _search_task(
            chat_id=chat_id,
            bot=context.bot,
            cancel_event=cancel_event,
            status_msg_id=status_msg_id,
        )
    )
    task.add_done_callback(_search_task_done_callback(chat_id, ud))
    context.user_data["search_task"] = task
    return SEARCHING



_DEFAULT_RUN: dict = {
    "applied": 0,
    "stopped_by_limit": False,
    "hh_temp_unavailable": False,
    "session_invalid": False,
}


async def _search_task(
    *,
    chat_id: int,
    bot,
    cancel_event: asyncio.Event,
    status_msg_id: int,
) -> None:
    from src.pipeline import run_user_pipeline

    aborted_session_invalid = False
    last_stopped_by_limit = False

    settings = await db.get_user_settings(chat_id)
    if not settings or not settings.is_complete():
        log.warning("search_task.incomplete_settings_abort", chat_id=chat_id)
        await db.clear_search_session(
            chat_id, log_event="search_session.cleared_on_task_finish"
        )
        return

    daily_limit = get_config().hh.search.daily_apply_limit
    result: dict = dict(_DEFAULT_RUN)
    try:
        result = await run_user_pipeline(
            chat_id=chat_id,
            hh_email=settings.hh_email,
            hh_password=settings.hh_password,
            hhtoken=settings.hhtoken,
            resume_id=settings.resume_id or "",
            keywords=settings.keywords,
            cover_letter=settings.cover_letter or "",
            cancel_event=cancel_event,
            bot=bot,
        )
    except asyncio.CancelledError:
        log.info("search_task.pipeline_wait_cancelled", chat_id=chat_id)
        raise
    except Exception as exc:
        log.exception("search_task.error", chat_id=chat_id, error=str(exc))
        result = dict(_DEFAULT_RUN)
    finally:
        await db.clear_search_session(
            chat_id, log_event="search_session.cleared_on_task_finish"
        )

    if result.get("session_invalid"):
        log.error("search_task.session_invalid_stop", chat_id=chat_id)
        try:
            await bot.send_message(
                chat_id=chat_id,
                text=(
                    "⚠️ <b>Сессия hh.ru недействительна.</b>\n\n"
                    "Откройте настройки в меню и войдите снова."
                ),
                parse_mode=ParseMode.HTML,
            )
        except Exception as notify_exc:
            log.warning(
                "search_task.session_invalid_notify_failed",
                chat_id=chat_id,
                error=str(notify_exc),
            )
        try:
            await bot.edit_message_text(
                chat_id=chat_id,
                message_id=status_msg_id,
                text="⚠️ Сессия hh.ru истекла. Обновите вход в настройках.",
                parse_mode=ParseMode.HTML,
                reply_markup=back_keyboard(),
            )
        except Exception as edit_exc:
            log.warning(
                "search_task.session_invalid_edit_failed",
                chat_id=chat_id,
                error=str(edit_exc),
            )
        aborted_session_invalid = True

    last_stopped_by_limit = bool(result.get("stopped_by_limit"))
    if result.get("hh_temp_unavailable"):
        log.warning("search_task.hh_temp_unavailable", chat_id=chat_id)

    if cancel_event.is_set() or aborted_session_invalid:
        return

    stats = await db.get_today_stats(chat_id)
    if last_stopped_by_limit:
        text = (
            "🛑 <b>Достигнут дневной лимит откликов.</b>\n\n"
            "Подробный итог отправлен отдельным сообщением выше."
        )
    else:
        text = format_final_summary(
            stats, daily_limit=daily_limit, stopped_by_limit=False
        )

    try:
        await bot.edit_message_text(
            chat_id=chat_id,
            message_id=status_msg_id,
            text=text,
            parse_mode=ParseMode.HTML,
            reply_markup=back_keyboard(),
            disable_web_page_preview=True,
        )
    except Exception as edit_exc:
        log.warning("search_task.final_edit_failed", chat_id=chat_id, error=str(edit_exc))
        try:
            await bot.send_message(
                chat_id=chat_id,
                text=text,
                parse_mode=ParseMode.HTML,
                reply_markup=back_keyboard(),
                disable_web_page_preview=True,
            )
        except Exception as send_exc:
            log.exception(
                "search_task.final_summary_send_failed",
                chat_id=chat_id,
                error=str(send_exc),
            )


async def _send_post_stop_summary(
    chat_id: int,
    task: asyncio.Task | None,
    bot,
    daily_limit: int,
) -> None:
    forced_cancel = False
    if isinstance(task, asyncio.Task) and not task.done():
        try:
            await asyncio.wait_for(asyncio.shield(task), timeout=240.0)
        except asyncio.TimeoutError:
            log.error("handlers.stop_search.forced_cancel_after_wait", chat_id=chat_id)
            forced_cancel = True
            task.cancel()
            try:
                await asyncio.wait_for(task, timeout=20.0)
            except asyncio.CancelledError:
                log.info(
                    "handlers.stop_search.task_stopped_after_cancel",
                    chat_id=chat_id,
                )
            except asyncio.TimeoutError:
                log.warning(
                    "handlers.stop_search.cancel_cleanup_timeout",
                    chat_id=chat_id,
                )
            except Exception as follow_exc:
                log.warning(
                    "handlers.stop_search.cancel_followup_error",
                    chat_id=chat_id,
                    error=str(follow_exc),
                )
        except asyncio.CancelledError:
            log.info(
                "handlers.stop_search.summary_wait_cancelled",
                chat_id=chat_id,
            )
    if forced_cancel:
        log.info("search_session.cleared_on_forced_cancel", chat_id=chat_id)
        await db.clear_search_session(chat_id)
    try:
        stats = await db.get_today_stats(chat_id)
        text = format_final_summary(
            stats, daily_limit=daily_limit, stopped_by_limit=False
        )
        await bot.send_message(
            chat_id=chat_id,
            text=text,
            parse_mode=ParseMode.HTML,
            reply_markup=back_keyboard(),
            disable_web_page_preview=True,
        )
    except Exception as summary_exc:
        log.exception(
            "handlers.stop_search.summary_failed",
            chat_id=chat_id,
            error=str(summary_exc),
        )


async def _background_search_shutdown(chat_id: int, task: asyncio.Task | None) -> None:
    """Дожимает остановку поиска после быстрого выхода в меню."""
    if not isinstance(task, asyncio.Task) or task.done():
        log.info("handlers.back_to_menu.bg_no_pending_task", chat_id=chat_id)
        return
    try:
        await asyncio.wait_for(asyncio.shield(task), timeout=_BG_SHUTDOWN_GRACEFUL_SEC)
        log.info("handlers.back_to_menu.bg_graceful_complete", chat_id=chat_id)
    except asyncio.CancelledError:
        log.info("handlers.back_to_menu.bg_wait_cancelled", chat_id=chat_id)
    except asyncio.TimeoutError:
        log.warning("handlers.back_to_menu.bg_forcing_cancel", chat_id=chat_id)
        task.cancel()
        try:
            await asyncio.wait_for(task, timeout=_BG_SHUTDOWN_HARD_SEC)
        except asyncio.CancelledError:
            log.info("handlers.back_to_menu.bg_task_cancelled", chat_id=chat_id)
        except asyncio.TimeoutError:
            log.error("handlers.back_to_menu.bg_hard_cancel_timeout", chat_id=chat_id)
        except Exception as exc:
            log.warning(
                "handlers.back_to_menu.bg_cancel_followup",
                chat_id=chat_id,
                error=str(exc),
            )
        log.info("search_session.cleared_on_forced_cancel", chat_id=chat_id)
        await db.clear_search_session(chat_id)
    except Exception as exc:
        log.warning(
            "handlers.back_to_menu.bg_unexpected",
            chat_id=chat_id,
            error=str(exc),
        )


async def stop_search(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    query = update.callback_query
    await query.answer()

    chat_id = update.effective_chat.id
    cancel_event = context.user_data.get("cancel_event")
    if isinstance(cancel_event, asyncio.Event):
        cancel_event.set()
    await db.clear_search_session(chat_id, log_event="search_session.cleared_on_stop_search")

    t = context.user_data.get("search_task")
    config = get_config()
    await query.edit_message_text(
        "⏹ <b>Останавливаю поиск…</b>\n\nИтоговая статистика — следующим сообщением.",
        parse_mode=ParseMode.HTML,
    )
    asyncio.create_task(
        _send_post_stop_summary(
            chat_id,
            t if isinstance(t, asyncio.Task) else None,
            context.bot,
            config.hh.search.daily_apply_limit,
        )
    )
    return SEARCHING


async def back_to_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    query = update.callback_query
    await query.answer()
    chat_id = update.effective_chat.id

    ce = context.user_data.get("cancel_event")
    if isinstance(ce, asyncio.Event):
        ce.set()
    await db.clear_search_session(chat_id, log_event="search_session.cleared_on_back_to_menu")
    task = context.user_data.get("search_task")
    asyncio.create_task(
        _background_search_shutdown(
            chat_id,
            task if isinstance(task, asyncio.Task) else None,
        )
    )

    settings = await db.get_user_settings(chat_id)
    await query.edit_message_text(
        _settings_text(settings) if settings else "Настройки не найдены.",
        parse_mode=ParseMode.HTML,
        reply_markup=main_menu_keyboard() if settings else None,
    )
    return MAIN_MENU


# ---------------------------------------------------------------------------
# Регистрация
# ---------------------------------------------------------------------------


def register_handlers(app: Application) -> None:
    conv = ConversationHandler(
        entry_points=[CommandHandler("start", cmd_start)],
        states={
            SETUP_KEYWORDS: [MessageHandler(filters.TEXT & ~filters.COMMAND, setup_keywords)],
            SETUP_COVER_LETTER: [
                CallbackQueryHandler(setup_letter_skip, pattern="^skip_letter$"),
                MessageHandler(filters.TEXT & ~filters.COMMAND, setup_letter_text),
            ],
            SETUP_EMAIL: [MessageHandler(filters.TEXT & ~filters.COMMAND, setup_email)],
            SETUP_PASSWORD: [MessageHandler(filters.TEXT & ~filters.COMMAND, setup_password)],
            SETUP_OTP: [MessageHandler(filters.TEXT & ~filters.COMMAND, setup_otp)],
            SELECT_RESUME: [CallbackQueryHandler(select_resume, pattern=r"^resume:")],
            MAIN_MENU: [
                CallbackQueryHandler(
                    main_menu,
                    pattern=r"^(edit_keywords|edit_letter|edit_credentials|start_search|reset_applications)$",
                )
            ],
            EDIT_KEYWORDS: [MessageHandler(filters.TEXT & ~filters.COMMAND, edit_keywords)],
            EDIT_COVER_LETTER: [
                CallbackQueryHandler(edit_letter_skip, pattern="^skip_letter$"),
                MessageHandler(filters.TEXT & ~filters.COMMAND, edit_letter_text),
            ],
            EDIT_CREDENTIALS: [
                MessageHandler(filters.TEXT & ~filters.COMMAND, edit_credentials_email)
            ],
            EDIT_PASSWORD: [MessageHandler(filters.TEXT & ~filters.COMMAND, edit_password)],
            EDIT_OTP: [MessageHandler(filters.TEXT & ~filters.COMMAND, edit_otp)],
            SEARCHING: [
                CallbackQueryHandler(stop_search, pattern="^stop_search$"),
                CallbackQueryHandler(back_to_menu, pattern="^back_to_menu$"),
            ],
        },
        fallbacks=[CommandHandler("start", cmd_start)],
        per_user=True,
        per_chat=True,
        per_message=False,
        allow_reentry=True,
    )
    app.add_handler(conv)
    app.add_error_handler(on_error)


async def on_error(update: object, context: ContextTypes.DEFAULT_TYPE) -> None:
    log.error(
        "telegram.handler.error",
        error=str(context.error),
        update=str(update)[:1000] if update else None,
        exc_info=context.error,
    )
