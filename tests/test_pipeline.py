"""
Тесты логики фильтрации вакансий.

Покрывают:
  - exclude_keywords фильтр
  - has_test флаг
  - salary_text генерацию
  - snippet HTML-очистку
"""
from __future__ import annotations

from src.hh.schemas import SnippetSchema, VacancySchema
from tests.conftest import make_vacancy


class TestExcludeKeywords:
    def test_exact_match_excluded(self) -> None:
        assert make_vacancy(name="Junior Python Developer").matches_exclude(["junior"]) is True

    def test_case_insensitive(self) -> None:
        assert make_vacancy(name="JUNIOR Python Developer").matches_exclude(["junior"]) is True

    def test_not_excluded(self) -> None:
        assert make_vacancy(name="Senior Python Backend").matches_exclude(["junior", "1c"]) is False

    def test_empty_exclude_list(self) -> None:
        assert make_vacancy(name="Junior Python").matches_exclude([]) is False

    def test_partial_word_match(self) -> None:
        assert make_vacancy(name="Internship Python").matches_exclude(["intern"]) is True


class TestHasTest:
    def test_false_by_default(self) -> None:
        assert make_vacancy().has_test is False

    def test_true_when_set(self) -> None:
        assert make_vacancy(has_test=True).has_test is True


class TestSalaryText:
    def test_salary_range(self) -> None:
        text = make_vacancy(salary_from=100_000, salary_to=200_000).salary_text
        assert "100" in text and "200" in text and "RUR" in text

    def test_salary_only_from(self) -> None:
        text = make_vacancy(salary_from=150_000, salary_to=None).salary_text
        assert "от" in text and "150" in text

    def test_salary_only_to(self) -> None:
        assert "до" in make_vacancy(salary_from=None, salary_to=200_000).salary_text

    def test_no_salary(self) -> None:
        v = VacancySchema(id="1", name="Test", salary=None, alternate_url="https://hh.ru/v/1")
        assert v.salary_text == "з/п не указана"


class TestApplyUrl:
    def test_uses_apply_url(self) -> None:
        v = make_vacancy(apply_alternate_url="https://hh.ru/apply/1")
        assert v.effective_apply_url == "https://hh.ru/apply/1"

    def test_fallback_to_alternate(self) -> None:
        v = make_vacancy(alternate_url="https://hh.ru/vacancy/999", apply_alternate_url=None)
        assert v.effective_apply_url == "https://hh.ru/vacancy/999"


class TestSnippetHtmlStrip:
    def test_html_tags_stripped(self) -> None:
        s = SnippetSchema(
            requirement="<highlighttext>Python</highlighttext> и FastAPI",
            responsibility="<b>Разработка</b> API",
        )
        assert "<" not in (s.requirement or "")
        assert "Python" in (s.requirement or "")
        assert "Разработка" in (s.responsibility or "")
