"""Строгая релевантность: язык literal, экосистема не заменяет язык."""
from __future__ import annotations

from src.hh.relevance import is_vacancy_relevant_to_query
from tests.conftest import make_vacancy


def test_middle_java_rejects_go_vacancy() -> None:
    v = make_vacancy(
        name="Middle Go Developer",
        requirement="Разработка на Go",
        responsibility="Microservices",
    )
    ok, _, _, miss_lang = is_vacancy_relevant_to_query("Middle Java developer", v)
    assert not ok
    assert "java" in miss_lang


def test_middle_java_accepts_java_vacancy() -> None:
    v = make_vacancy(
        name="Middle Java developer",
        requirement="Spring, Java 17",
    )
    ok, _, _, miss_lang = is_vacancy_relevant_to_query("Middle Java developer", v)
    assert ok
    assert not miss_lang


def test_phrase_in_title_strong_accept() -> None:
    v = make_vacancy(name="Senior Java Backend Engineer")
    ok, _, _, _ = is_vacancy_relevant_to_query("Java Backend", v)
    assert ok


def test_java_developer_rejects_spring_only() -> None:
    v = make_vacancy(
        name="Spring Developer",
        requirement="Spring Boot, микросервисы",
    )
    ok, _, _, miss_lang = is_vacancy_relevant_to_query("Java developer", v)
    assert not ok
    assert "java" in miss_lang


def test_middle_java_rejects_middle_spring_only() -> None:
    v = make_vacancy(name="Middle Spring Developer", requirement="Spring Cloud")
    ok, _, _, miss_lang = is_vacancy_relevant_to_query("Middle Java developer", v)
    assert not ok
    assert "java" in miss_lang


def test_python_developer_rejects_django_only() -> None:
    v = make_vacancy(
        name="Django Developer",
        requirement="Django REST, PostgreSQL",
    )
    ok, _, _, miss_lang = is_vacancy_relevant_to_query("Python developer", v)
    assert not ok
    assert "python" in miss_lang


def test_python_developer_accepts_python_in_snippet() -> None:
    v = make_vacancy(
        name="Backend Engineer",
        requirement="Python 3.11, FastAPI",
    )
    ok, _, _, _ = is_vacancy_relevant_to_query("Python developer", v)
    assert ok


def test_go_developer_accepts_golang_title() -> None:
    v = make_vacancy(name="Golang Developer", requirement="Go 1.22")
    ok, _, _, _ = is_vacancy_relevant_to_query("Go developer", v)
    assert ok
