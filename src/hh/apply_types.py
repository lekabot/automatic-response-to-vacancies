"""Типизированный результат отклика на вакансию (HH.ru)."""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class ApplyStatus(str, Enum):
    """Результат одной попытки / итоговой классификации apply."""

    APPLIED = "APPLIED"
    ALREADY_APPLIED = "ALREADY_APPLIED"
    TEMP_ERROR = "TEMP_ERROR"
    PERM_ERROR = "PERM_ERROR"
    TIMEOUT = "TIMEOUT"
    AUTH_ERROR = "AUTH_ERROR"


@dataclass(frozen=True, slots=True)
class ApplyOutcome:
    status: ApplyStatus
    http_status: int | None
    error_code: str | None
    error_message: str | None
    retryable: bool

    @staticmethod
    def applied() -> "ApplyOutcome":
        return ApplyOutcome(
            status=ApplyStatus.APPLIED,
            http_status=200,
            error_code=None,
            error_message=None,
            retryable=False,
        )

    @staticmethod
    def already_applied() -> "ApplyOutcome":
        return ApplyOutcome(
            status=ApplyStatus.ALREADY_APPLIED,
            http_status=200,
            error_code="alreadyApplied",
            error_message=None,
            retryable=False,
        )
