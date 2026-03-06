"""Форматирование текстов сообщений Telegram (HTML parse_mode)."""
from __future__ import annotations

from src.hh.schemas import VacancySchema

# Максимальная длина текста карточки (Telegram limit 4096, берём с запасом)
_MAX_SNIPPET_LEN = 300


def format_vacancy_card(vacancy: VacancySchema) -> str:
    """
    Карточка вакансии для отправки в Telegram.

    Использует HTML-разметку (parse_mode=HTML).
    """
    lines: list[str] = []

    # Заголовок
    lines.append(f"💼 <b>{_esc(vacancy.name)}</b>")

    # Работодатель
    if vacancy.employer.name:
        lines.append(f"🏢 {_esc(vacancy.employer.name)}")

    # Зарплата
    lines.append(f"💰 {_esc(vacancy.salary_text)}")

    # Регион
    if vacancy.area.name:
        lines.append(f"📍 {_esc(vacancy.area.name)}")

    # График
    if vacancy.schedule and vacancy.schedule.name:
        lines.append(f"🕐 {_esc(vacancy.schedule.name)}")

    # Snippet — краткое описание
    if vacancy.snippet:
        if vacancy.snippet.requirement:
            req = _esc(vacancy.snippet.requirement[:_MAX_SNIPPET_LEN])
            lines.append(f"\n📋 <i>Требования:</i> {req}")
        if vacancy.snippet.responsibility:
            resp = _esc(vacancy.snippet.responsibility[:_MAX_SNIPPET_LEN])
            lines.append(f"📝 <i>Обязанности:</i> {resp}")

    # ID для отладки
    lines.append(f"\n<code>ID: {vacancy.id}</code>")

    return "\n".join(lines)


def format_summary(stats: dict) -> str:
    """Вечерний summary."""
    counts = stats.get("counts", {})
    requires_test = stats.get("requires_test", [])

    lines: list[str] = [
        "📊 <b>Итоги дня — HH Vacancy Assistant</b>",
        "",
        f"📤 Предложено вакансий: <b>{counts.get('sent', 0)}</b>",
        f"✅ Подтверждено откликов: <b>{counts.get('applied_confirmed', 0)}</b>",
        f"❌ Пропущено: <b>{counts.get('skipped', 0)}</b>",
        f"🧪 Требует теста (не предложено): <b>{counts.get('requires_test', 0)}</b>",
    ]

    if requires_test:
        lines.append("\n🧪 <b>Вакансии с тестом:</b>")
        for v in requires_test[:10]:
            title = _esc(v.get("title", "—"))
            employer = _esc(v.get("employer", ""))
            url = v.get("url", "")
            lines.append(f"  • <a href='{url}'>{title}</a> — {employer}")
        if len(requires_test) > 10:
            lines.append(f"  ... и ещё {len(requires_test) - 10}")

    return "\n".join(lines)


def format_apply_result(title: str, employer: str, success: bool) -> str:
    if success:
        return f"✅ Отклик отправлен!\n<b>{_esc(title)}</b> — {_esc(employer)}"
    return f"⚠️ Не удалось отправить отклик автоматически.\n<b>{_esc(title)}</b>\nПожалуйста, откликнитесь вручную."


def format_run_started(keywords: list[str]) -> str:
    kw = ", ".join(f"<code>{_esc(k)}</code>" for k in keywords[:5])
    return f"🔍 Запущен поиск вакансий...\nКлючевые слова: {kw}"


def format_run_finished(sent: int, skipped_test: int) -> str:
    return (
        f"✅ Поиск завершён.\n"
        f"📤 Отправлено карточек: <b>{sent}</b>\n"
        f"🧪 С тестом (пропущено): <b>{skipped_test}</b>"
    )


def _esc(text: str) -> str:
    """Экранирует специальные HTML-символы."""
    return (
        text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )
