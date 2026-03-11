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
from src.bot.formatters import format_final_summary, format_hourly_summary
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

log = structlog.get_logger(__name__)

(
    SETUP_KEYWORDS,
    SETUP_COVER_LETTER,
    SETUP_EMAIL,
    SETUP_OTP,
    SELECT_RESUME,
    MAIN_MENU,
    EDIT_KEYWORDS,
    EDIT_COVER_LETTER,
    EDIT_CREDENTIALS,
    EDIT_OTP,
    SEARCHING,
) = range(11)

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
    await db.save_user_settings(update.effective_chat.id, cover_letter=update.message.text.strip())
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

    otp_msg = (
        "📧 <b>Код уже отправлен</b>\n\n"
        "На ваш email уже был отправлен 4-значный код — проверьте входящие (и папку «Спам»).\n"
        "Введите его ниже:"
    ) if login_info.get("already_sent") else _PROMPT_OTP
    await wait.edit_text(otp_msg, parse_mode=ParseMode.HTML)
    return SETUP_OTP


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
        count = await db.reset_applied_vacancies()
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
    await db.save_user_settings(update.effective_chat.id, cover_letter=update.message.text.strip())
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

    otp_msg = (
        "📧 <b>Код уже отправлен</b>\n\n"
        "На ваш email уже был отправлен 4-значный код — проверьте входящие (и папку «Спам»).\n"
        "Введите его ниже:"
    ) if login_info.get("already_sent") else _PROMPT_OTP
    await wait.edit_text(otp_msg, parse_mode=ParseMode.HTML)
    return EDIT_OTP


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


async def _start_search(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    chat_id = update.effective_chat.id
    settings = await db.get_user_settings(chat_id)
    config = get_config()

    if not settings or not settings.is_complete():
        await update.callback_query.answer("⚠️ Настройки неполные.", show_alert=True)
        return MAIN_MENU

    cancel_event = asyncio.Event()
    context.user_data["cancel_event"] = cancel_event

    kw_list = "\n".join(f"• {_esc(k)}" for k in settings.keywords[:10])
    msg = await update.callback_query.edit_message_text(
        f"🚀 <b>Начали откликаться за вас!</b>\n\nПоиск по словам:\n{kw_list}\n\n"
        "Каждый час присылаю статистику.",
        parse_mode=ParseMode.HTML,
        reply_markup=stop_keyboard(),
    )
    status_msg_id = (
        msg.message_id if hasattr(msg, "message_id") else update.callback_query.message.message_id
    )

    job = context.job_queue.run_repeating(
        _hourly_job,
        interval=3600,
        first=3600,
        chat_id=chat_id,
        name=f"hourly_{chat_id}",
        data={"cancel_event": cancel_event, "daily_limit": config.hh.search.daily_apply_limit},
    )
    context.user_data["hourly_job"] = job

    task = asyncio.create_task(
        _search_task(
            chat_id=chat_id,
            settings=settings,
            bot=context.bot,
            cancel_event=cancel_event,
            status_msg_id=status_msg_id,
            job_queue=context.job_queue,
            daily_limit=config.hh.search.daily_apply_limit,
        )
    )
    context.user_data["search_task"] = task
    return SEARCHING


async def _hourly_job(context: ContextTypes.DEFAULT_TYPE) -> None:
    data = context.job.data
    if data["cancel_event"].is_set():
        context.job.schedule_removal()
        return
    stats = await db.get_today_stats()
    await context.bot.send_message(
        chat_id=context.job.chat_id,
        text=format_hourly_summary(stats, daily_limit=data["daily_limit"]),
        parse_mode=ParseMode.HTML,
    )


async def _search_task(
        *,
        chat_id: int,
        settings: UserSettings,
        bot,
        cancel_event: asyncio.Event,
        status_msg_id: int,
        job_queue,
        daily_limit: int,
) -> None:
    from src.pipeline import run_user_pipeline

    try:
        result = await run_user_pipeline(
            chat_id=chat_id,
            hh_email=settings.hh_email,
            hh_password=settings.hh_password,
            hhtoken=settings.hhtoken,
            resume_id=settings.resume_id,
            keywords=settings.keywords,
            cover_letter=settings.cover_letter or "",
            cancel_event=cancel_event,
        )
    except Exception as exc:
        log.exception("search_task.error", error=str(exc))
        result = {"applied": 0, "stopped_by_limit": False}

    for job in job_queue.get_jobs_by_name(f"hourly_{chat_id}"):
        job.schedule_removal()

    # Если пользователь остановил вручную — stop_search уже показал итог
    if cancel_event.is_set():
        return

    stats = await db.get_today_stats()
    text = format_final_summary(
        stats, daily_limit=daily_limit, stopped_by_limit=result["stopped_by_limit"]
    )

    try:
        await bot.edit_message_text(
            chat_id=chat_id, message_id=status_msg_id, text=text,
            parse_mode=ParseMode.HTML, reply_markup=back_keyboard(),
            disable_web_page_preview=True,
        )
    except Exception:
        await bot.send_message(
            chat_id=chat_id, text=text, parse_mode=ParseMode.HTML,
            reply_markup=back_keyboard(), disable_web_page_preview=True,
        )


async def stop_search(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    query = update.callback_query
    await query.answer("Останавливаю...")

    cancel_event: asyncio.Event | None = context.user_data.get("cancel_event")
    if cancel_event:
        cancel_event.set()

    for job in context.job_queue.get_jobs_by_name(f"hourly_{update.effective_chat.id}"):
        job.schedule_removal()

    stats = await db.get_today_stats()
    config = get_config()
    text = format_final_summary(stats, daily_limit=config.hh.search.daily_apply_limit, stopped_by_limit=False)
    await query.edit_message_text(
        text, parse_mode=ParseMode.HTML, reply_markup=back_keyboard(), disable_web_page_preview=True
    )
    return SEARCHING


async def back_to_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> int:
    query = update.callback_query
    await query.answer()

    task: asyncio.Task | None = context.user_data.get("search_task")
    if task and not task.done():
        task.cancel()

    settings = await db.get_user_settings(update.effective_chat.id)
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
