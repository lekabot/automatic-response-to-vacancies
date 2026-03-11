"""Pydantic-схемы для ответов HH.ru API."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, field_validator


class SalarySchema(BaseModel):
    from_: int | None = Field(None, alias="from")
    to: int | None = None
    currency: str | None = None
    gross: bool | None = None

    model_config = {"populate_by_name": True}

    def as_text(self) -> str:
        parts: list[str] = []
        if self.from_:
            parts.append(f"от {self.from_:,}")
        if self.to:
            parts.append(f"до {self.to:,}")
        if parts:
            currency = self.currency or "RUB"
            suffix = " (gross)" if self.gross else ""
            return " ".join(parts) + f" {currency}{suffix}"
        return "з/п не указана"


class EmployerSchema(BaseModel):
    id: str | None = None
    name: str = ""
    url: str | None = None


class AreaSchema(BaseModel):
    id: str | None = None
    name: str = ""


class ScheduleSchema(BaseModel):
    id: str | None = None
    name: str = ""


class SnippetSchema(BaseModel):
    requirement: str | None = None
    responsibility: str | None = None

    @field_validator("requirement", "responsibility", mode="before")
    @classmethod
    def strip_html(cls, v: Any) -> str | None:
        if v is None:
            return None
        import re
        return re.sub(r"<[^>]+>", "", str(v)).strip()


class VacancySchema(BaseModel):
    """Одна вакансия из ответа /vacancies API."""

    id: str
    name: str
    has_test: bool = False
    salary: SalarySchema | None = None
    employer: EmployerSchema = EmployerSchema()
    area: AreaSchema = AreaSchema()
    schedule: ScheduleSchema | None = None
    alternate_url: str = ""
    apply_alternate_url: str | None = None
    snippet: SnippetSchema | None = None
    published_at: str | None = None

    @property
    def vacancy_url(self) -> str:
        return self.alternate_url

    @property
    def effective_apply_url(self) -> str:
        """URL для кнопки «Откликнуться»."""
        return self.apply_alternate_url or self.alternate_url

    @property
    def salary_text(self) -> str:
        return self.salary.as_text() if self.salary else "з/п не указана"

    def matches_exclude(self, exclude_keywords: list[str]) -> bool:
        """True если название содержит хотя бы одно исключающее слово."""
        name_lower = self.name.lower()
        return any(kw.lower() in name_lower for kw in exclude_keywords)

    def matches_keywords(self, keywords: list[str]) -> bool:
        """
        True если название содержит хотя бы одно слово из любого ключевого запроса.

        Пример: keywords=["Python разработчик", "Senior Python"]
        → True для "Python-разработчик backend", "Senior Python developer"
        → False для "DevOps инженер", "BI-аналитик"
        """
        if not keywords:
            return True
        name_lower = self.name.lower()
        for keyword in keywords:
            # Разбиваем фразу на отдельные слова и проверяем каждое
            words = [w.strip() for w in keyword.lower().split() if len(w.strip()) > 2]
            if any(word in name_lower for word in words):
                return True
        return False


class VacanciesResponse(BaseModel):
    """Ответ /vacancies."""

    items: list[VacancySchema] = []
    found: int = 0
    pages: int = 0
    per_page: int = 20
    page: int = 0
