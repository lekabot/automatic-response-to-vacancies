"""Pytest fixtures."""
from __future__ import annotations

import pytest

from src.hh.schemas import (
    AreaSchema,
    EmployerSchema,
    SalarySchema,
    ScheduleSchema,
    SnippetSchema,
    VacancySchema,
)


def make_vacancy(
    *,
    vacancy_id: str = "12345",
    name: str = "Python Developer",
    employer: str = "Acme Corp",
    has_test: bool = False,
    salary_from: int | None = 150_000,
    salary_to: int | None = 250_000,
    area: str = "Москва",
    schedule: str = "Удалённая работа",
    alternate_url: str = "https://hh.ru/vacancy/12345",
    apply_alternate_url: str | None = "https://hh.ru/applicant/vacancy_response?vacancyId=12345",
    requirement: str | None = "Python 3.10+, FastAPI, PostgreSQL",
    responsibility: str | None = "Разработка backend API",
) -> VacancySchema:
    return VacancySchema(
        id=vacancy_id,
        name=name,
        has_test=has_test,
        employer=EmployerSchema(name=employer),
        area=AreaSchema(name=area),
        schedule=ScheduleSchema(name=schedule),
        salary=SalarySchema(**{"from": salary_from, "to": salary_to, "currency": "RUR"}),
        alternate_url=alternate_url,
        apply_alternate_url=apply_alternate_url,
        snippet=SnippetSchema(requirement=requirement, responsibility=responsibility),
    )
