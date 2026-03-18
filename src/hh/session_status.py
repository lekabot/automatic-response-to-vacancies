"""Результат проверки сессии hh.ru (не bool)."""
from __future__ import annotations

from enum import StrEnum


class SessionValidationStatus(StrEnum):
    VALID = "valid"
    INVALID = "invalid"
    TEMP_UNAVAILABLE = "temp_unavailable"
