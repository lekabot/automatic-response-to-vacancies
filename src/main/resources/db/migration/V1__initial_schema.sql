-- =============================================================================
-- V1__initial_schema.sql
-- Начальная схема hh-vacancy-assistant
-- =============================================================================

-- ─── Тип для статуса вакансии ─────────────────────────────────────────────────

CREATE TYPE vacancy_status AS ENUM (
    'IN_PROGRESS',
    'APPLIED',
    'ALREADY_APPLIED',
    'SKIPPED',
    'REQUIRES_TEST',
    'APPLY_TIMEOUT',
    'APPLY_TEMP_ERROR',
    'APPLY_PERM_ERROR'
);

-- ─── Настройки пользователей ──────────────────────────────────────────────────

CREATE TABLE user_settings (
    chat_id                     BIGINT      PRIMARY KEY,
    keywords_json               TEXT        NOT NULL DEFAULT '[]',
    cover_letter                TEXT,
    hh_email                    TEXT,
    hhtoken                     TEXT,
    resume_id                   TEXT,
    resume_title                TEXT,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Поисковая сессия (null = нет активной сессии)
    search_session_started_at   TIMESTAMPTZ,
    last_hourly_report_slot     INTEGER
);

COMMENT ON TABLE  user_settings IS 'Настройки поиска и состояние сессии конкретного Telegram-пользователя';
COMMENT ON COLUMN user_settings.hhtoken IS 'Cookie hhtoken после авторизации. Хранится зашифрованным в production через vault/secrets.';
COMMENT ON COLUMN user_settings.search_session_started_at IS 'Момент старта активной сессии поиска; null = сессия не активна';
COMMENT ON COLUMN user_settings.last_hourly_report_slot IS 'Последний отправленный hourly-слот; slot = floor((now - started_at) / 3600)';

-- ─── Обработанные вакансии ────────────────────────────────────────────────────

CREATE TABLE vacancies_seen (
    chat_id                 BIGINT              NOT NULL,
    vacancy_id              VARCHAR(64)         NOT NULL,
    title                   TEXT                NOT NULL,
    employer                TEXT                NOT NULL DEFAULT '',
    url                     TEXT                NOT NULL DEFAULT '',
    salary_text             TEXT,
    status                  VARCHAR(32)         NOT NULL,    -- VacancyStatus enum value
    attempt_count           INTEGER             NOT NULL DEFAULT 0,
    last_error              TEXT,
    last_attempt_at         TIMESTAMPTZ,
    next_retry_at           TIMESTAMPTZ,
    processing_started_at   TIMESTAMPTZ,
    seen_at                 TIMESTAMPTZ         NOT NULL DEFAULT now(),

    PRIMARY KEY (chat_id, vacancy_id)
);

COMMENT ON TABLE  vacancies_seen IS 'Состояние обработки вакансий (конечный автомат) per Telegram-пользователь';
COMMENT ON COLUMN vacancies_seen.status IS 'Текстовое значение VacancyStatus enum (не FK на тип — проще в миграциях)';
COMMENT ON COLUMN vacancies_seen.processing_started_at IS 'Начало IN_PROGRESS — используется для определения истечения lease';
COMMENT ON COLUMN vacancies_seen.next_retry_at IS 'Время следующего retry для APPLY_TIMEOUT / APPLY_TEMP_ERROR';
COMMENT ON COLUMN vacancies_seen.attempt_count IS '1-based: количество выполненных попыток отклика';

-- ─── Индексы ──────────────────────────────────────────────────────────────────

-- Основной поиск при polling-цикле: статус + время
CREATE INDEX ix_vacancies_seen_chat_status
    ON vacancies_seen (chat_id, status);

-- Подсчёт откликов за сутки
CREATE INDEX ix_vacancies_seen_chat_seen_at
    ON vacancies_seen (chat_id, seen_at)
    WHERE status = 'APPLIED';

-- Поиск записей с истёкшим retry
CREATE INDEX ix_vacancies_seen_next_retry
    ON vacancies_seen (chat_id, next_retry_at)
    WHERE next_retry_at IS NOT NULL;

-- Поиск зависших IN_PROGRESS (для lease expiry)
CREATE INDEX ix_vacancies_seen_processing
    ON vacancies_seen (chat_id, processing_started_at)
    WHERE status = 'IN_PROGRESS';

-- Активные сессии для шедулера
CREATE INDEX ix_user_settings_session
    ON user_settings (search_session_started_at)
    WHERE search_session_started_at IS NOT NULL;
