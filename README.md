# HH Vacancy Assistant 🤖

Telegram-бот на Python 3.12, который:
- **Ищет вакансии** на hh.ru по ключевым словам через официальный API + HTML-парсинг
- **Автоматически откликается** с сопроводительным письмом через твой hh.ru аккаунт (session-auth)
- **Отправляет карточки вакансий** в Telegram с кнопками подтверждения
- **Ежедневный отчёт** в 18:00 по Москве

---

## Структура репозитория

```
hh-vacancy-assistant/
├── main.py                    # Точка входа
├── config.yaml                # Фильтры, расписание, регионы
├── .env.example               # Шаблон секретов
├── pyproject.toml
├── alembic.ini
├── Dockerfile                 # Multi-stage, python:3.12-slim
├── docker-compose.yml
├── Makefile
│
├── src/
│   ├── config.py              # Pydantic-settings + YAML
│   ├── models.py              # SQLAlchemy ORM (VacancySeen, ActionLog, Run)
│   ├── database.py            # Async session, repository helpers
│   │
│   ├── hh/
│   │   ├── schemas.py         # Pydantic-схемы ответов HH API
│   │   ├── client.py          # httpx-клиент: rate-limit, retry, login, search, apply
│   │   └── parser.py          # BeautifulSoup: парсинг HTML-страниц hh.ru
│   │
│   ├── bot/
│   │   ├── app.py             # Фабрика Application + JobQueue
│   │   ├── handlers.py        # /start, /whoami, /run, /status, callbacks
│   │   ├── formatters.py      # Текст карточек, summary (HTML parse_mode)
│   │   └── keyboards.py       # InlineKeyboard: URL-кнопки + callback-кнопки
│   │
│   └── pipeline.py            # Утренний поиск + вечерний отчёт
│
├── migrations/
│   ├── env.py                 # Alembic + async SQLite
│   ├── script.py.mako
│   └── versions/
│       └── 001_initial.py     # Создание таблиц + индексов
│
├── tests/
│   ├── conftest.py            # Фикстуры (make_vacancy)
│   ├── test_pipeline.py       # Тесты фильтрации вакансий
│   └── test_formatters.py     # Тесты форматирования сообщений
│
└── data/                      # SQLite база (монтируется в Docker)
```

---

## Быстрый старт

### 1. Создание Telegram-бота

1. Открой [@BotFather](https://t.me/BotFather) в Telegram
2. Отправь `/newbot`
3. Придумай имя: `HH Vacancy Assistant`
4. Придумай username: `hh_vacancy_xxx_bot`
5. Скопируй **токен** вида `1234567890:ABCdef...`

### 2. Узнать свой chat_id

**Способ А (через бота):**
1. Запусти бота (см. шаг 5)
2. Отправь ему `/start` или `/whoami`
3. Бот ответит: `Ваш chat_id: 123456789`

**Способ Б (через @userinfobot):**
1. Напиши [@userinfobot](https://t.me/userinfobot) — он сразу покажет твой ID

### 3. Получить ID резюме на hh.ru

1. Зайди на [hh.ru](https://hh.ru) → «Моё резюме»
2. Открой резюме — URL будет вида: `https://hh.ru/resume/abc123def456789`
3. Скопируй ID: `abc123def456789`

### 4. Настроить конфиг

```bash
# Создать .env из шаблона
make env
# Или вручную:
cp .env.example .env
```

Заполни `.env`:
```dotenv
TELEGRAM_BOT_TOKEN=1234567890:ABCdef...
HH_USERNAME=your@email.com
HH_PASSWORD=your_password
HH_RESUME_ID=abc123def456789
COVER_LETTER=Добрый день! Меня заинтересовала вакансия «{title}» в {employer}...
```

Заполни `config.yaml`:
```yaml
hh:
  search:
    include_keywords:
      - "Python developer"
      - "Python backend"
    exclude_keywords:
      - "junior"
      - "1C"
    area:
      - 1    # Москва
      - 2    # Санкт-Петербург
    schedule:
      - "remote"
    published_within_hours: 24

telegram:
  chat_id: 123456789   # ← вставь свой chat_id

runtime:
  timezone: "Europe/Moscow"
  run_time: "10:00"
  summary_time: "18:00"
```

### 5. Запуск через Docker Compose (рекомендуется)

```bash
# Сборка образа
make build

# Применить миграции БД
make migrate

# Запустить бота
make up

# Следить за логами
make logs

# Остановить
make down
```

### 6. Локальный запуск (для разработки)

```bash
python -m venv .venv
source .venv/bin/activate
make install

# Применить миграции
make migrate-local

# Запустить
python main.py
```

---

## Telegram UX

### Карточка вакансии

Каждая вакансия приходит в виде:

```
💼 Senior Python Developer
🏢 Яндекс
💰 от 300,000 до 500,000 RUR
📍 Москва
🕐 Удалённая работа

📋 Требования: Python 3.10+, FastAPI, PostgreSQL...
📝 Обязанности: Разработка backend API...

ID: 98765432

[🔗 Открыть вакансию]  [📨 Откликнуться]
[✅ Подтвердить отклик]  [❌ Пропустить]
```

**URL-кнопки** (`alternate_url`, `apply_alternate_url`) открывают hh.ru напрямую.  
**Callback-кнопки** записывают статус в БД.

### Команды

| Команда | Действие |
|---------|----------|
| `/start` | Приветствие + chat_id |
| `/whoami` | Показать твой chat_id |
| `/run` | Запустить поиск прямо сейчас |
| `/status` | Статистика за сегодня |
| `/summary` | Полный отчёт за день |

---

## HH.ru API: использованные поля

| Поле | Использование |
|------|---------------|
| `id` | Первичный ключ вакансии, дедупликация |
| `name` | Заголовок карточки |
| `has_test` | `true` → статус `REQUIRES_TEST`, не предлагаем отклик |
| `alternate_url` | Ссылка на вакансию (кнопка «Открыть вакансию») |
| `apply_alternate_url` | Ссылка для отклика (кнопка «Откликнуться»); если `null` → fallback на `alternate_url` |
| `salary` | Зарплатная вилка для карточки |
| `employer.name` | Название компании |
| `area.name` | Регион |
| `schedule.name` | График работы |
| `snippet` | Краткие требования/обязанности (автозачистка HTML-тегов) |

Документация: [github.com/hhru/api](https://github.com/hhru/api)

---

## Хранилище (SQLite)

```sql
-- Все увиденные вакансии
vacancies_seen(vacancy_id PK, title, employer, url, apply_url,
               salary_text, status, first_seen_at, last_seen_at, message_id)

-- Статусы: NEW | SENT | APPLIED_CONFIRMED | SKIPPED | REQUIRES_TEST

-- Журнал действий
actions_log(id, ts, vacancy_id, action, payload_json)

-- Журнал запусков с метриками
runs(run_id, started_at, finished_at, counts_json)
```

Миграции: `make migrate` / `alembic upgrade head`

---

## Переменные окружения

| Переменная | Обязательная | Описание |
|------------|:---:|---------|
| `TELEGRAM_BOT_TOKEN` | ✅ | Токен от @BotFather |
| `HH_USERNAME` | ✅ | Email аккаунта hh.ru |
| `HH_PASSWORD` | ✅ | Пароль аккаунта hh.ru |
| `HH_RESUME_ID` | ✅ | ID резюме для отклика |
| `COVER_LETTER` | — | Шаблон письма (`{title}`, `{employer}`) |
| `CONFIG_PATH` | — | Путь к config.yaml (default: `config.yaml`) |
| `LOG_LEVEL` | — | `DEBUG`/`INFO`/`WARNING` (default: `INFO`) |

---

## Тесты

```bash
make test
# или
pytest -v
```

Покрытие:
- `test_pipeline.py` — фильтрация по `exclude_keywords`, `has_test`, `salary_text`, `apply_url`
- `test_formatters.py` — форматирование карточек, summary, HTML-экранирование

---

## Технологии

| Компонент | Библиотека |
|-----------|-----------|
| HTTP-клиент | `httpx[http2]` |
| HTML-парсинг | `beautifulsoup4` + `lxml` |
| Telegram Bot | `python-telegram-bot[job-queue]` |
| Планировщик | `JobQueue` (встроен в PTB, wrapper над APScheduler) |
| Конфиг | `pydantic-settings` + `pyyaml` |
| ORM | `SQLAlchemy 2.0 async` + `aiosqlite` |
| Миграции | `alembic` |
| Retry | `tenacity` |
| Логирование | `structlog` |
| Тесты | `pytest` + `pytest-asyncio` |

---

## Важные примечания

- **Сопроводительное письмо** поддерживает переменные: `{title}` и `{employer}`.
- **Вакансии с тестом** (`has_test=true`) автоматически переводятся в `REQUIRES_TEST` и не включаются в рассылку — они появятся только в вечернем отчёте.
- **Дедупликация**: одна вакансия не будет предложена повторно в течение `retention_days` (по умолчанию 30 дней).
- **Rate limiting**: токен-бакет с настраиваемым QPS (по умолчанию 2 req/s, burst 5) — hh.ru блокирует при превышении.
- Если автоматический отклик не удался — карточка всё равно отправляется в Telegram с предложением откликнуться вручную.
