"""
HTML-парсер hh.ru.

Используется как дополнение к API:
  - Извлечение вакансий из HTML-страницы поиска (window.__initial_state__).
  - Проверка доступности вакансии по её странице.
  - Поиск кнопки «Откликнуться» и формы отклика.
"""
from __future__ import annotations

import json
import re
from typing import Any

import structlog
from bs4 import BeautifulSoup

from src.hh.schemas import VacancySchema

log = structlog.get_logger(__name__)

# Паттерн для JSON-стейта, встроенного в страницу
_STATE_RE = re.compile(r"window\.__initial_state__\s*=\s*(\{.+?\})(?:;|</script>)", re.DOTALL)


def parse_vacancies_from_html(html: str) -> list[VacancySchema]:
    """
    Пытается извлечь вакансии из HTML-страницы /search/vacancy.

    hh.ru рендерит данные в тег <script> как window.__initial_state__ = {...}.
    Если не найдено — возвращает пустой список.
    """
    match = _STATE_RE.search(html)
    if not match:
        log.debug("hh.parser.no_initial_state")
        return []

    try:
        state: dict[str, Any] = json.loads(match.group(1))
    except json.JSONDecodeError as exc:
        log.warning("hh.parser.json_error", error=str(exc))
        return []

    # Структура state зависит от версии hh.ru, пробуем несколько путей
    vacancies_raw: list[dict] = []
    for path in (
        ["vacancySearch", "vacancies"],
        ["vacanciesSearchResult", "vacancies"],
        ["searchResult", "vacancies"],
    ):
        node: Any = state
        for key in path:
            if isinstance(node, dict) and key in node:
                node = node[key]
            else:
                node = None
                break
        if isinstance(node, list):
            vacancies_raw = node
            break

    if not vacancies_raw:
        log.debug("hh.parser.vacancies_not_found_in_state")
        return []

    result: list[VacancySchema] = []
    for raw in vacancies_raw:
        try:
            result.append(VacancySchema.model_validate(raw))
        except Exception as exc:
            log.warning("hh.parser.vacancy_parse_error", error=str(exc))

    return result


def check_vacancy_available(html: str) -> bool:
    """
    Возвращает True если страница вакансии доступна (не «вакансия удалена»).
    """
    soup = BeautifulSoup(html, "lxml")
    # Признаки удалённой / закрытой вакансии
    indicators = [
        "вакансия удалена",
        "вакансия не найдена",
        "vacancy has been deleted",
        "vacancy not found",
        "нет активных вакансий",
    ]
    page_text = soup.get_text().lower()
    return not any(ind in page_text for ind in indicators)


def extract_vacancy_apply_url(html: str) -> str | None:
    """Извлекает URL формы отклика из страницы вакансии."""
    soup = BeautifulSoup(html, "lxml")

    # Ищем кнопку/ссылку «Откликнуться»
    for tag in soup.find_all(["a", "button"]):
        text = tag.get_text(strip=True).lower()
        if "откликнуться" in text or "respond" in text:
            href = tag.get("href")
            if href and href.startswith("http"):
                return str(href)

    return None
