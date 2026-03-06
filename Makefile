.PHONY: build up down logs restart migrate test lint install

# ─── Docker ──────────────────────────────────────────────────────────────────

build:
	docker compose build --no-cache

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f --tail=100

restart:
	docker compose restart hh-assistant

# ─── Database ─────────────────────────────────────────────────────────────────

migrate:
	docker compose run --rm hh-assistant \
		python -c "from alembic.config import Config; from alembic import command; c = Config('alembic.ini'); command.upgrade(c, 'head')"

migrate-local:
	alembic upgrade head

# ─── Development ──────────────────────────────────────────────────────────────

install:
	pip install -e ".[dev]"

test:
	pytest -v --tb=short

lint:
	ruff check src/ tests/

run-local:
	python main.py

# ─── Helpers ──────────────────────────────────────────────────────────────────

env:
	@if [ ! -f .env ]; then \
		cp .env.example .env; \
		echo "✅ .env создан из .env.example — заполни его!"; \
	else \
		echo "⚠️  .env уже существует"; \
	fi

shell:
	docker compose exec hh-assistant /bin/bash

status:
	docker compose ps
