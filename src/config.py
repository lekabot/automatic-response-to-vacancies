"""
Конфигурация приложения.

Принцип:
  - Структурные параметры (фильтры, расписание) → config.yaml
  - Секреты и учётные данные → .env / переменные окружения
"""
from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


# ---------------------------------------------------------------------------
# Sub-models (YAML section)
# ---------------------------------------------------------------------------


class RateLimitConfig(BaseModel):
    qps: float = 2.0
    burst: int = 5


class SearchConfig(BaseModel):
    include_keywords: list[str] = ["Python developer"]
    exclude_keywords: list[str] = []
    area: list[int] = [1]
    schedule: list[str] = ["remote", "fullDay"]
    employment: list[str] = ["full"]
    published_within_hours: int = 24
    max_vacancies_per_run: int = 200


class HHConfig(BaseModel):
    user_agent: str = "HHVacancyAssistant/1.0"
    rate_limit: RateLimitConfig = RateLimitConfig()
    search: SearchConfig = SearchConfig()


class TelegramYamlConfig(BaseModel):
    chat_id: int = 0


class RuntimeConfig(BaseModel):
    timezone: str = "Europe/Moscow"
    run_time: str = "10:00"
    summary_time: str = "18:00"

    @field_validator("run_time", "summary_time")
    @classmethod
    def validate_time_format(cls, v: str) -> str:
        parts = v.split(":")
        if len(parts) != 2 or not all(p.isdigit() for p in parts):
            raise ValueError(f"Время должно быть в формате HH:MM, получено: {v!r}")
        h, m = int(parts[0]), int(parts[1])
        if not (0 <= h < 24 and 0 <= m < 60):
            raise ValueError(f"Некорректное время: {v!r}")
        return v


class StorageConfig(BaseModel):
    driver: str = "sqlite"
    sqlite_path: str = "/data/bot.sqlite"
    retention_days: int = 30


class YamlConfig(BaseModel):
    """Полная конфигурация из config.yaml"""

    hh: HHConfig = HHConfig()
    telegram: TelegramYamlConfig = TelegramYamlConfig()
    runtime: RuntimeConfig = RuntimeConfig()
    storage: StorageConfig = StorageConfig()


# ---------------------------------------------------------------------------
# Env-based settings (secrets)
# ---------------------------------------------------------------------------


class EnvSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Telegram
    telegram_bot_token: SecretStr

    # HH.ru credentials
    hh_username: str = ""
    hh_password: SecretStr = SecretStr("")
    hh_resume_id: str = ""

    # Сопроводительное письмо (поддерживает {title}, {employer})
    cover_letter: str = (
        "Добрый день!\n\nМеня заинтересовала вакансия «{title}» в компании {employer}.\n"
        "Готов рассмотреть предложение и обсудить детали.\n\nС уважением."
    )

    config_path: str = "config.yaml"


# ---------------------------------------------------------------------------
# Unified AppConfig
# ---------------------------------------------------------------------------


class AppConfig:
    """Единая точка доступа ко всей конфигурации."""

    def __init__(self, env: EnvSettings, yaml_cfg: YamlConfig) -> None:
        self.env = env
        self.yaml = yaml_cfg

    # --- shortcuts ---
    @property
    def bot_token(self) -> str:
        return self.env.telegram_bot_token.get_secret_value()

    @property
    def chat_id(self) -> int:
        return self.yaml.telegram.chat_id

    @property
    def hh(self) -> HHConfig:
        return self.yaml.hh

    @property
    def runtime(self) -> RuntimeConfig:
        return self.yaml.runtime

    @property
    def storage(self) -> StorageConfig:
        return self.yaml.storage

    @property
    def db_url(self) -> str:
        return f"sqlite+aiosqlite:///{self.storage.sqlite_path}"

    def render_cover_letter(self, title: str, employer: str) -> str:
        """Подставляет переменные в шаблон сопроводительного письма."""
        return self.env.cover_letter.replace("\\n", "\n").format(
            title=title, employer=employer
        )


def _load_yaml(path: str) -> dict[str, Any]:
    p = Path(path)
    if not p.exists():
        return {}
    return yaml.safe_load(p.read_text(encoding="utf-8")) or {}


@lru_cache(maxsize=1)
def get_config() -> AppConfig:
    env = EnvSettings()
    raw = _load_yaml(env.config_path)
    yaml_cfg = YamlConfig.model_validate(raw)
    return AppConfig(env=env, yaml_cfg=yaml_cfg)
