"""Форматирование сообщений Telegram (HTML parse_mode)."""
from __future__ import annotations


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def format_hourly_summary(stats: dict, daily_limit: int) -> str:
    applied = stats["applied"]
    failed = stats["failed"]
    retry_later = stats.get("retry_later", 0)
    skipped = stats["skipped"]
    requires_test = stats["requires_test"]
    pct = int(applied / daily_limit * 100) if daily_limit else 0

    return (
        "📊 <b>Почасовой отчёт</b>\n\n"
        f"✅ Откликнулся: <b>{applied}</b> / {daily_limit} ({pct}%)\n"
        f"⚠️ Ошибка отклика (вручную): <b>{failed}</b>\n"
        f"🔁 Отложено (сеть/таймаут): <b>{retry_later}</b>\n"
        f"🧪 С тестом (пропущено): <b>{requires_test}</b>\n"
        f"🚫 Отфильтровано: <b>{skipped}</b>"
    )


def format_final_summary(stats: dict, daily_limit: int, *, stopped_by_limit: bool) -> str:
    applied = stats["applied"]
    failed = stats["failed"]
    failed_vacancies = stats["failed_vacancies"]

    lines: list[str] = []
    if stopped_by_limit:
        lines.append(f"🛑 <b>Достигнут дневной лимит ({daily_limit} откликов)!</b>\n")
    else:
        lines.append("✅ <b>Поиск завершён!</b>\n")

    lines.append(f"Откликнулся: <b>{applied}</b> вакансий")

    if failed_vacancies:
        lines.append(f"\n⚠️ <b>Не удалось откликнуться ({failed}) — откликнитесь вручную:</b>")
        for v in failed_vacancies[:15]:
            salary = f" · {esc(v['salary_text'])}" if v.get("salary_text") else ""
            lines.append(
                f"• <a href='{v['url']}'>{esc(v['title'])}</a> — {esc(v['employer'])}{salary}"
            )
        if len(failed_vacancies) > 15:
            lines.append(f"  … и ещё {len(failed_vacancies) - 15}")

    return "\n".join(lines)
