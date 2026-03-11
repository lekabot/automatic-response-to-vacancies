"""SQLAlchemy ORM models."""
from __future__ import annotations

import enum
import json
from datetime import datetime

from sqlalchemy import DateTime, Enum, Integer, String, Text, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class VacancyStatus(str, enum.Enum):
    APPLIED = "APPLIED"                    # Новый отклик, отправлен в этой сессии
    ALREADY_APPLIED = "ALREADY_APPLIED"    # hh.ru: уже откликались ранее — не считаем
    APPLY_FAILED = "APPLY_FAILED"          # API вернул ошибку — можно откликнуться вручную
    SKIPPED = "SKIPPED"                    # Отфильтровано по exclude_keywords
    REQUIRES_TEST = "REQUIRES_TEST"        # Вакансия с тестом — пропускаем


class VacancySeen(Base):
    """Каждая вакансия, которую бот обработал."""

    __tablename__ = "vacancies_seen"

    vacancy_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    employer: Mapped[str] = mapped_column(Text, default="")
    url: Mapped[str] = mapped_column(Text, default="")
    salary_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[VacancyStatus] = mapped_column(
        Enum(VacancyStatus), nullable=False
    )
    seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
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
