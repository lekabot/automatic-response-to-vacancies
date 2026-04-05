# HH Vacancy Assistant — Java/Quarkus

Сервис автоматических откликов на вакансии hh.ru через Telegram-бот.

## Технологии

| Компонент | Технология |
|-----------|-----------|
| Runtime | Java 25 LTS |
| Framework | Quarkus 3.33 LTS |
| Build | Gradle 8.x |
| Database | PostgreSQL 17 |
| DB Access | jOOQ 3.20 (без ORM) |
| JSON | Jackson |
| HTML parsing | jsoup 1.22.1 |
| Telegram | com.github.pengrad:java-telegram-bot-api:9.6.0 |
| HTTP | OkHttp 4.x |
| Migrations | Flyway |
| Tests | JUnit 5, JaCoCo, PIT, Testcontainers |

## Архитектура

```
adapter/telegram          ← Telegram long-polling, command handlers (тонкий адаптер)
application/              ← Бизнес-логика: сервисы, scheduler, политики
domain/                   ← Модель, порты (интерфейсы), политики retry
infrastructure/           ← Реализации: hh HTTP-клиенты, HTML-парсер, jOOQ repos
config/                   ← Типизированные @ConfigMapping
```

Telegram **не является** центральным процессом. `SearchSessionScheduler` работает независимо,
все уведомления идут через `NotificationPort` (реализован Telegram-адаптером).

## Быстрый старт (dev)

```bash
# Требуется Java 25+ и Docker
./gradlew quarkusDev
```

Quarkus DevServices автоматически поднимет PostgreSQL через Testcontainers.

## Запуск в production

```bash
# Создать .env
cp .env.example .env
# Заполнить TELEGRAM_BOT_TOKEN, DB_PASSWORD

# Запустить
docker compose up -d
```

## Тесты

```bash
./gradlew test                          # unit-тесты
./gradlew integrationTest               # интеграция с PostgreSQL (Testcontainers)
./gradlew contractTest                  # контрактные тесты HH API и Telegram
./gradlew jacocoTestReport              # Coverage report
./gradlew jacocoTestCoverageVerification # Проверка 90%+ coverage
./gradlew pitest                        # Mutation testing (PIT) для domain/application
```

## Сборка образов

```bash
# JVM (production default)
./gradlew buildJvmImage

# Native (опционально, ~20 мин)
./gradlew buildNativeImage
```

## Конфигурация

Все параметры — через env-переменные. Полный список в `src/main/resources/application.properties`.

Обязательные:
- `TELEGRAM_BOT_TOKEN` — токен Telegram-бота
- `DB_PASSWORD` — пароль PostgreSQL

## Мониторинг

| Endpoint | Описание |
|----------|---------|
| `GET /health/live` | Liveness probe |
| `GET /health/ready` | Readiness probe |
| `GET /metrics` | Prometheus metrics |

## Структура базы данных

Таблицы: `user_settings`, `vacancies_seen`.
Схема в `src/main/resources/db/migration/V1__initial_schema.sql`.
Применяется автоматически при старте (Flyway).
