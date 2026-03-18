"""SQLAlchemy ORM models — vacancy processing state machine (per Telegram user)."""
from __future__ import annotations

import enum
import json
from datetime import datetime

from sqlalchemy import DateTime, Enum, Index, Integer, String, Text, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class VacancyStatus(str, enum.Enum):
    """Конечный автомат обработки вакансии в пайплайне."""

    IN_PROGRESS = "IN_PROGRESS"
    APPLIED = "APPLIED"
    ALREADY_APPLIED = "ALREADY_APPLIED"
    SKIPPED = "SKIPPED"
    REQUIRES_TEST = "REQUIRES_TEST"
    APPLY_TIMEOUT = "APPLY_TIMEOUT"
    APPLY_TEMP_ERROR = "APPLY_TEMP_ERROR"
    APPLY_PERM_ERROR = "APPLY_PERM_ERROR"


class VacancySeen(Base):
    """
    Вакансия, обработанная пайплайном для конкретного chat_id (Telegram).
    Составной PK: один и тот же vacancy_id у разных пользователей — разные строки.
    """

    __tablename__ = "vacancies_seen"
    __table_args__ = (
        Index("ix_vacancies_seen_chat_status", "chat_id", "status"),
        Index("ix_vacancies_seen_chat_seen_at", "chat_id", "seen_at"),
        Index("ix_vacancies_seen_chat_next_retry", "chat_id", "next_retry_at"),
        Index("ix_vacancies_seen_chat_processing", "chat_id", "processing_started_at"),
    )

    chat_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    vacancy_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    employer: Mapped[str] = mapped_column(Text, default="")
    url: Mapped[str] = mapped_column(Text, default="")
    salary_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[VacancyStatus] = mapped_column(
        Enum(VacancyStatus, values_callable=lambda x: [e.value for e in x]),
        nullable=False,
    )
    seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    attempt_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    last_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    next_retry_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    processing_started_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )


class UserSettings(Base):
    """Настройки поиска конкретного пользователя Telegram."""

    __tablename__ = "user_settings"

    chat_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    keywords_json: Mapped[str] = mapped_column(Text, default="[]")
    cover_letter: Mapped[str | None] = mapped_column(Text, nullable=True)
    hh_email: Mapped[str | None] = mapped_column(Text, nullable=True)
    hh_password: Mapped[str | None] = mapped_column(Text, nullable=True)
    hhtoken: Mapped[str | None] = mapped_column(Text, nullable=True)
    resume_id: Mapped[str | None] = mapped_column(Text, nullable=True)
    resume_title: Mapped[str | None] = mapped_column(Text, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    search_session_started_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    last_hourly_report_slot: Mapped[int | None] = mapped_column(Integer, nullable=True)

    @property
    def keywords(self) -> list[str]:
        try:
            return json.loads(self.keywords_json or "[]")
        except Exception:
            return []

    @keywords.setter
    def keywords(self, value: list[str]) -> None:
        self.keywords_json = json.dumps(value, ensure_ascii=False)

    def is_complete(self) -> bool:
        has_auth = bool(self.hhtoken) or bool(self.hh_password)
        return bool(self.keywords and self.hh_email and has_auth and self.resume_id)
