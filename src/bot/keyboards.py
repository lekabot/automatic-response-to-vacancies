"""InlineKeyboard фабрики."""
from __future__ import annotations

from telegram import InlineKeyboardButton, InlineKeyboardMarkup


def main_menu_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        [
            [
                InlineKeyboardButton("✏️ Ключевые слова", callback_data="edit_keywords"),
                InlineKeyboardButton("✏️ Письмо", callback_data="edit_letter"),
            ],
            [InlineKeyboardButton("✏️ Аккаунт hh.ru", callback_data="edit_credentials")],
            [InlineKeyboardButton("▶️ Запустить поиск", callback_data="start_search")],
        ]
    )


def skip_letter_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        [[InlineKeyboardButton("⏭ Без письма", callback_data="skip_letter")]]
    )


def resume_keyboard(resumes: list[dict]) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        [
            [InlineKeyboardButton(r.get("title", f"Резюме {i+1}"), callback_data=f"resume:{r['id']}")]
            for i, r in enumerate(resumes[:10])
        ]
    )


def stop_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        [[InlineKeyboardButton("⏹ Остановить поиск", callback_data="stop_search")]]
    )


def back_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        [[InlineKeyboardButton("◀️ Вернуться в меню", callback_data="back_to_menu")]]
    )
