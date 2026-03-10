"""Конфигурация приложения.

Секреты (.env):   TELEGRAM_BOT_TOKEN
Параметры поиска: config.yaml
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class RateLimitConfig(BaseModel):
    qps: float = 2.0
    burst: int = 5


class SearchConfig(BaseModel):
    exclude_keywords: list[str] = []
    area: list[int] = [1]
    schedule: list[str] = ["remote", "fullDay"]
    employment: list[str] = ["full"]
    published_within_hours: int = 24
    max_vacancies_per_run: int = 200
    daily_apply_limit: int = 200


class HHConfig(BaseModel):
    user_agent: str = "HHVacancyAssistant/1.0"
    rate_limit: RateLimitConfig = RateLimitConfig()
    search: SearchConfig = SearchConfig()


class StorageConfig(BaseModel):
    sqlite_path: str = "/data/bot.sqlite"
    retention_days: int = 30


class YamlConfig(BaseModel):
    hh: HHConfig = HHConfig()
    storage: StorageConfig = StorageConfig()


class EnvSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    telegram_bot_token: SecretStr
    config_path: str = "config.yaml"


class AppConfig:
    def __init__(self, env: EnvSettings, yaml_cfg: YamlConfig) -> None:
        self.env = env
        self.hh = yaml_cfg.hh
        self.storage = yaml_cfg.storage

    @property
    def bot_token(self) -> str:
        return self.env.telegram_bot_token.get_secret_value()

    @property
    def db_url(self) -> str:
        return f"sqlite+aiosqlite:///{self.storage.sqlite_path}"


@lru_cache(maxsize=1)
def get_config() -> AppConfig:
    env = EnvSettings()
    raw: dict[str, Any] = {}
    p = Path(env.config_path)
    if p.exists():
        raw = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
    return AppConfig(env=env, yaml_cfg=YamlConfig.model_validate(raw))
