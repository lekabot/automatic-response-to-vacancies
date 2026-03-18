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


def _format_test_vacancies_block(rows: list[dict], total: int, *, limit: int) -> list[str]:
    lines: list[str] = []
    if not rows and total == 0:
        return lines
    lines.append("\n<b>Vacancies with test tasks</b>")
    for r in rows[:limit]:
        title = esc(r.get("title") or "—")
        emp = esc(r.get("employer") or "—")
        url = (r.get("url") or "").strip()
        if url:
            lines.append(f"• <a href=\"{esc(url)}\">{title}</a> — {emp}")
        else:
            lines.append(f"• {title} — {emp} <i>(URL unavailable)</i>")
    more = total - len(rows)
    if more > 0:
        lines.append(f"<i>… and {more} more</i>")
    return lines


def format_session_progress_report(
    stats: dict,
    test_vacancies: list[dict],
    test_vacancies_total: int,
    daily_limit: int,
    *,
    is_final: bool,
    test_block_limit: int = 20,
) -> str:
    """Промежуточный почасовой или финальный отчёт по окну сессии."""
    applied = stats["applied"]
    failed = stats["failed"]
    retry_later = stats.get("retry_later", 0)
    skipped = stats["skipped"]
    requires_test_n = stats["requires_test"]
    pct = int(applied / daily_limit * 100) if daily_limit else 0

    if is_final:
        head = "🛑 <b>Финальный отчёт</b> — достигнут дневной лимит откликов\n\n"
    else:
        head = "📊 <b>Почасовой отчёт</b>\n\n"

    body = (
        f"✅ Откликнулся: <b>{applied}</b> / {daily_limit} ({pct}%)\n"
        f"⚠️ Ошибка отклика (вручную): <b>{failed}</b>\n"
        f"🔁 Отложено: <b>{retry_later}</b>\n"
        f"🧪 С тестом (всего в сессии): <b>{requires_test_n}</b>\n"
        f"🚫 Пропущено/отфильтровано: <b>{skipped}</b>"
    )
    lines = [head + body]
    lines.extend(
        _format_test_vacancies_block(
            test_vacancies, test_vacancies_total, limit=test_block_limit
        )
    )
    if is_final:
        lines.append(
            "\n<i>Сессия поиска завершена по лимиту. Можно запустить снова завтра или после сброса.</i>"
        )
    return "\n".join(lines)


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
