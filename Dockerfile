# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Builder — устанавливаем зависимости в отдельный virtualenv
# ─────────────────────────────────────────────────────────────────────────────
FROM python:3.12-slim AS builder

WORKDIR /build

# Системные зависимости для lxml
RUN apt-get update && apt-get install -y --no-install-recommends \
        gcc \
        libxml2-dev \
        libxslt-dev \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

RUN pip install --upgrade pip \
    && pip install --prefix=/install --no-cache-dir -r requirements.txt

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime — минимальный образ
# ─────────────────────────────────────────────────────────────────────────────
FROM python:3.12-slim AS runtime

LABEL org.opencontainers.image.title="HH Vacancy Assistant" \
      org.opencontainers.image.description="Telegram bot: HH.ru vacancy search & auto-apply" \
      org.opencontainers.image.version="1.0.0"

WORKDIR /app

# Системные runtime-зависимости
RUN apt-get update && apt-get install -y --no-install-recommends \
        libxml2 \
        libxslt1.1 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Копируем установленные пакеты из builder
COPY --from=builder /install /usr/local

# Копируем исходный код
COPY main.py .
COPY src/ ./src/
COPY migrations/ ./migrations/
COPY alembic.ini .
# config.yaml монтируется через volume или передаётся через bind mount
# config.yaml COPY config.yaml .

# Директория для SQLite-базы
RUN mkdir -p /data && chmod 777 /data

# Непривилегированный пользователь
RUN useradd --no-create-home --uid 1001 appuser \
    && chown -R appuser:appuser /app /data
USER appuser

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    LOG_LEVEL=INFO \
    CONFIG_PATH=/app/config.yaml

VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD python -c "import sys; sys.exit(0)"

CMD ["python", "main.py"]
