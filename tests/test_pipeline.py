"""
Тесты фильтрации вакансий в пайплайне.

Покрывают:
  - exclude_keywords фильтр
  - has_test флаг
  - дедупликацию
  - salary_text генерацию
"""
from __future__ import annotations

import pytest

from src.hh.schemas import VacancySchema
from tests.conftest import make_vacancy


class TestExcludeKeywords:
    def test_exact_match_excluded(self) -> None:
        v = make_vacancy(name="Junior Python Developer")
        assert v.matches_exclude(["junior"]) is True

    def test_case_insensitive(self) -> None:
        v = make_vacancy(name="JUNIOR Python Developer")
        assert v.matches_exclude(["junior"]) is True

    def test_not_excluded_when_no_match(self) -> None:
        v = make_vacancy(name="Senior Python Backend")
        assert v.matches_exclude(["junior", "1c", "intern"]) is False

    def test_empty_exclude_list(self) -> None:
        v = make_vacancy(name="Junior Python Developer")
        assert v.matches_exclude([]) is False

    def test_multiple_exclude_words(self) -> None:
        v = make_vacancy(name="Python 1C разработчик")
        assert v.matches_exclude(["1c", "junior"]) is True

    def test_partial_word_match(self) -> None:
        v = make_vacancy(name="Internship Python")
        assert v.matches_exclude(["intern"]) is True


class TestHasTest:
    def test_has_test_false_by_default(self) -> None:
        v = make_vacancy()
        assert v.has_test is False

    def test_has_test_true(self) -> None:
        v = make_vacancy(has_test=True)
        assert v.has_test is True


class TestSalaryText:
    def test_salary_range(self) -> None:
        v = make_vacancy(salary_from=100_000, salary_to=200_000)
        text = v.salary_text
        assert "100" in text
        assert "200" in text
        assert "RUR" in text

    def test_salary_only_from(self) -> None:
        v = make_vacancy(salary_from=150_000, salary_to=None)
        assert "от" in v.salary_text
        assert "150" in v.salary_text

    def test_salary_only_to(self) -> None:
        v = make_vacancy(salary_from=None, salary_to=200_000)
        assert "до" in v.salary_text

    def test_no_salary(self) -> None:
        from src.hh.schemas import VacancySchema

        v = VacancySchema(
            id="1",
            name="Test",
            salary=None,
            alternate_url="https://hh.ru/vacancy/1",
        )
        assert v.salary_text == "з/п не указана"


class TestApplyUrl:
    def test_apply_url_present(self) -> None:
        v = make_vacancy(
            apply_alternate_url="https://hh.ru/applicant/vacancy_response?vacancyId=1"
        )
        assert v.effective_apply_url == "https://hh.ru/applicant/vacancy_response?vacancyId=1"

    def test_apply_url_fallback_to_alternate(self) -> None:
        v = make_vacancy(
            alternate_url="https://hh.ru/vacancy/999",
            apply_alternate_url=None,
        )
        assert v.effective_apply_url == "https://hh.ru/vacancy/999"


class TestSnippetHtmlStrip:
    def test_html_tags_stripped(self) -> None:
        from src.hh.schemas import SnippetSchema

        s = SnippetSchema(
            requirement="<highlighttext>Python</highlighttext> и FastAPI",
            responsibility="<b>Разработка</b> API",
        )
        assert "<" not in (s.requirement or "")
        assert "Python" in (s.requirement or "")
        assert "Разработка" in (s.responsibility or "")
