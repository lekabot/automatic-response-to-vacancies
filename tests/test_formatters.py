"""Тесты форматирования сообщений."""
from __future__ import annotations

from src.bot.formatters import (
    format_final_summary,
    format_hourly_summary,
    format_session_progress_report,
)


def _stats(applied=0, failed=0, skipped=0, requires_test=0, vacancies=None):
    return {
        "applied": applied,
        "failed": failed,
        "skipped": skipped,
        "requires_test": requires_test,
        "failed_vacancies": vacancies or [],
    }


class TestFormatHourlySummary:
    def test_shows_applied_count(self) -> None:
        text = format_hourly_summary(_stats(applied=5), daily_limit=200)
        assert "5" in text

    def test_shows_daily_limit(self) -> None:
        text = format_hourly_summary(_stats(applied=100), daily_limit=200)
        assert "200" in text
        assert "100" in text

    def test_shows_percentage(self) -> None:
        text = format_hourly_summary(_stats(applied=50), daily_limit=200)
        assert "25%" in text

    def test_zero_applied(self) -> None:
        text = format_hourly_summary(_stats(), daily_limit=200)
        assert "0" in text


class TestFormatFinalSummary:
    def test_normal_completion(self) -> None:
        text = format_final_summary(_stats(applied=10), daily_limit=200, stopped_by_limit=False)
        assert "10" in text
        assert "✅" in text

    def test_stopped_by_limit(self) -> None:
        text = format_final_summary(_stats(applied=200), daily_limit=200, stopped_by_limit=True)
        assert "лимит" in text.lower()
        assert "🛑" in text

    def test_failed_vacancies_shown(self) -> None:
        vacancies = [
            {"title": "Python Dev", "employer": "Acme", "url": "https://hh.ru/v/1", "salary_text": ""},
        ]
        text = format_final_summary(_stats(failed=1, vacancies=vacancies), daily_limit=200, stopped_by_limit=False)
        assert "Python Dev" in text
        assert "Acme" in text
        assert "hh.ru/v/1" in text

    def test_many_failed_vacancies_truncated(self) -> None:
        vacancies = [
            {"title": f"Vacancy {i}", "employer": "Corp", "url": f"https://hh.ru/v/{i}", "salary_text": ""}
            for i in range(20)
        ]
        text = format_final_summary(_stats(failed=20, vacancies=vacancies), daily_limit=200, stopped_by_limit=False)
        assert "ещё" in text

    def test_html_escaped_in_vacancy_title(self) -> None:
        vacancies = [
            {"title": "<b>Bad</b>", "employer": "Corp", "url": "https://hh.ru/v/1", "salary_text": ""}
        ]
        text = format_final_summary(_stats(failed=1, vacancies=vacancies), daily_limit=200, stopped_by_limit=False)
        assert "<b>Bad</b>" not in text
        assert "&lt;b&gt;" in text


def test_session_report_includes_test_vacancy_urls() -> None:
    stats = {
        "applied": 1,
        "failed": 0,
        "retry_later": 0,
        "skipped": 0,
        "requires_test": 2,
        "already_applied": 0,
        "failed_vacancies": [],
        "counts": {},
    }
    tests = [
        {"title": "QA with test", "employer": "Co", "url": "https://hh.ru/v/99"},
        {"title": "No URL job", "employer": "X", "url": ""},
    ]
    text = format_session_progress_report(
        stats, tests, test_vacancies_total=5, daily_limit=200, is_final=False
    )
    assert "Vacancies with test tasks" in text
    assert "https://hh.ru/v/99" in text
    assert "URL unavailable" in text
    assert "and 3 more" in text or "3 more" in text
