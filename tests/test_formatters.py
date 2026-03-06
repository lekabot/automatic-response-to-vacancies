"""
Тесты форматирования сообщений Telegram.
"""
from __future__ import annotations

import pytest

from src.bot.formatters import (
    format_apply_result,
    format_run_finished,
    format_summary,
    format_vacancy_card,
)
from tests.conftest import make_vacancy


class TestFormatVacancyCard:
    def test_contains_title(self) -> None:
        v = make_vacancy(name="Senior Python Dev")
        card = format_vacancy_card(v)
        assert "Senior Python Dev" in card

    def test_contains_employer(self) -> None:
        v = make_vacancy(employer="Yandex")
        card = format_vacancy_card(v)
        assert "Yandex" in card

    def test_contains_salary(self) -> None:
        v = make_vacancy(salary_from=200_000, salary_to=350_000)
        card = format_vacancy_card(v)
        assert "200" in card
        assert "350" in card

    def test_contains_area(self) -> None:
        v = make_vacancy(area="Санкт-Петербург")
        card = format_vacancy_card(v)
        assert "Санкт-Петербург" in card

    def test_contains_vacancy_id(self) -> None:
        v = make_vacancy(vacancy_id="99999")
        card = format_vacancy_card(v)
        assert "99999" in card

    def test_html_entities_escaped(self) -> None:
        v = make_vacancy(name="<Script>Alert</Script>")
        card = format_vacancy_card(v)
        assert "<Script>" not in card
        assert "&lt;Script&gt;" in card

    def test_snippet_requirement_included(self) -> None:
        v = make_vacancy(requirement="Django, DRF, PostgreSQL")
        card = format_vacancy_card(v)
        assert "Django" in card

    def test_no_snippet(self) -> None:
        from src.hh.schemas import VacancySchema

        v = VacancySchema(
            id="1",
            name="Dev",
            employer=__import__("src.hh.schemas", fromlist=["EmployerSchema"]).EmployerSchema(
                name="Corp"
            ),
            alternate_url="https://hh.ru/vacancy/1",
        )
        card = format_vacancy_card(v)
        assert "Dev" in card


class TestFormatSummary:
    def test_summary_counts(self) -> None:
        stats = {
            "counts": {
                "sent": 10,
                "applied_confirmed": 5,
                "skipped": 3,
                "requires_test": 2,
            },
            "requires_test": [],
        }
        text = format_summary(stats)
        assert "10" in text
        assert "5" in text
        assert "3" in text
        assert "2" in text

    def test_requires_test_list(self) -> None:
        stats = {
            "counts": {"sent": 1, "applied_confirmed": 0, "skipped": 0, "requires_test": 1},
            "requires_test": [
                {"title": "QA Engineer", "employer": "Corp", "url": "https://hh.ru/v/1"}
            ],
        }
        text = format_summary(stats)
        assert "QA Engineer" in text
        assert "Corp" in text


class TestFormatApplyResult:
    def test_success(self) -> None:
        text = format_apply_result("Python Dev", "Acme", success=True)
        assert "✅" in text
        assert "Python Dev" in text

    def test_failure(self) -> None:
        text = format_apply_result("Python Dev", "Acme", success=False)
        assert "⚠️" in text
        assert "вручную" in text.lower()


class TestFormatRunFinished:
    def test_format(self) -> None:
        text = format_run_finished(sent=7, skipped_test=3)
        assert "7" in text
        assert "3" in text
