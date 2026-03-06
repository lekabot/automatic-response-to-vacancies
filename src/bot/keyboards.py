"""Фабрики InlineKeyboard для карточек вакансий."""
from __future__ import annotations

from telegram import InlineKeyboardButton, InlineKeyboardMarkup


def vacancy_keyboard(
    vacancy_id: str,
    vacancy_url: str,
    apply_url: str,
) -> InlineKeyboardMarkup:
    """
    Клавиатура карточки вакансии:
      Строка 1: [Открыть вакансию] [Откликнуться]
      Строка 2: [Подтвердить отклик ✅] [Пропустить ❌]
    """
    return InlineKeyboardMarkup(
        [
            [
                InlineKeyboardButton("🔗 Открыть вакансию", url=vacancy_url),
                InlineKeyboardButton("📨 Откликнуться", url=apply_url),
            ],
            [
                InlineKeyboardButton(
                    "✅ Подтвердить отклик",
                    callback_data=f"applied:{vacancy_id}",
                ),
                InlineKeyboardButton(
                    "❌ Пропустить",
                    callback_data=f"skip:{vacancy_id}",
                ),
            ],
        ]
    )


def already_applied_keyboard(vacancy_url: str) -> InlineKeyboardMarkup:
    """Клавиатура для вакансии, на которую уже откликнулись."""
    return InlineKeyboardMarkup(
        [[InlineKeyboardButton("🔗 Открыть вакансию", url=vacancy_url)]]
    )
