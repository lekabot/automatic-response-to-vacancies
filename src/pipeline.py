"""
Пайплайн поиска вакансий и автоматических откликов (изолированно по chat_id).

Статусная модель: claim IN_PROGRESS → apply → финальный статус.
"""
from __future__ import annotations

import re
import asyncio
from typing import TypedDict

import structlog

from src import database as db
from src.config import get_config
from src.database import ClaimReason
from src.hh.apply_types import ApplyStatus
from src.hh.client import HHClient
from src.hh.session_status import SessionValidationStatus
from src.hh.schemas import VacancySchema
from src.models import VacancyStatus

log = structlog.get_logger(__name__)

_PLACEHOLDER_RE = re.compile(r"\{([^{}]*)\}")


def validate_cover_letter_braces(text: str) -> tuple[bool, str | None]:
    """Проверка парности { } (без требования только title/employer)."""
    depth = 0
    for c in text:
        if c == "{":
            depth += 1
            if depth > 20:
                return False, "Слишком много открывающих «{»"
        elif c == "}":
            depth -= 1
            if depth < 0:
                return False, "Лишняя закрывающая скобка «}»"
    if depth != 0:
        return False, "Непарные фигурные скобки { }. Проверьте шаблон письма."
    return True, None


def render_cover_letter(template: str, *, title: str, employer: str) -> str:
    """
    Подставляет только {title} и {employer}; остальное в фигурных скобках остаётся как текст
    (например {Kafka}, {Spring}).
    """
    if not (template or "").strip():
        return ""
    s = template.replace("\\n", "\n")

    def repl(m: re.Match[str]) -> str:
        key = (m.group(1) or "").strip()
        if key == "title":
            return title
        if key == "employer":
            return employer
        return m.group(0)

    return _PLACEHOLDER_RE.sub(repl, s)


class PipelineResult(TypedDict):
    applied: int
    stopped_by_limit: bool
    hh_temp_unavailable: bool
    session_invalid: bool


async def run_user_pipeline(
    *,
    chat_id: int,
    hh_email: str,
    hh_password: str | None,
    hhtoken: str | None = None,
    resume_id: str,
    keywords: list[str],
    cover_letter: str,
    cancel_event: asyncio.Event,
) -> PipelineResult:
    config = get_config()
    result: PipelineResult = {
        "applied": 0,
        "stopped_by_limit": False,
        "hh_temp_unavailable": False,
        "session_invalid": False,
    }
    lease_min = config.hh.search.vacancy_lease_minutes
    heartbeat_n = max(1, config.hh.search.pipeline_heartbeat_every)
    apply_total = config.hh.search.apply_total_timeout_seconds
    per_attempt = config.hh.search.apply_per_attempt_timeout_seconds
    retention = config.storage.retention_days

    async with HHClient(
        user_agent=config.hh.user_agent,
        qps=config.hh.rate_limit.qps,
        burst=config.hh.rate_limit.burst,
        hhtoken=hhtoken,
    ) as hh:
        if hhtoken:
            st = await hh.validate_session_status()
            if st is SessionValidationStatus.INVALID:
                log.error(
                    "pipeline.session_invalid",
                    chat_id=chat_id,
                    hint="reauth_via_bot",
                )
                result["session_invalid"] = True
                return result
            if st is SessionValidationStatus.TEMP_UNAVAILABLE:
                log.warning(
                    "pipeline.hh_temp_unavailable_skip_cycle",
                    chat_id=chat_id,
                )
                result["hh_temp_unavailable"] = True
                return result
            logged_in = True
        else:
            logged_in = await hh.login(hh_email, hh_password or "")
        if not logged_in:
            log.error("pipeline.login_failed", chat_id=chat_id)
            return result

        vacancies = await _collect_vacancies(hh, keywords, config)
        total_list = len(vacancies)
        log.info("pipeline.collected", total=total_list, chat_id=chat_id)

        processed = 0
        run_applied = 0
        run_failed_perm = 0

        for idx, vacancy in enumerate(vacancies):
            if cancel_event.is_set():
                break

            today_count = await db.get_applied_today_count(chat_id)
            if today_count >= config.hh.search.daily_apply_limit:
                result["stopped_by_limit"] = True
                log.info(
                    "pipeline.daily_limit_reached",
                    limit=config.hh.search.daily_apply_limit,
                    chat_id=chat_id,
                )
                break

            if vacancy.matches_exclude(config.hh.search.exclude_keywords):
                await db.upsert_vacancy_skip_or_test(
                    chat_id=chat_id,
                    vacancy_id=vacancy.id,
                    title=vacancy.name,
                    employer=vacancy.employer.name,
                    url=vacancy.vacancy_url,
                    salary_text=vacancy.salary_text,
                    status=VacancyStatus.SKIPPED,
                )
                processed += 1
                if processed % heartbeat_n == 0:
                    _heartbeat(
                        chat_id=chat_id,
                        processed=processed,
                        applied=run_applied,
                        failed=run_failed_perm,
                        remaining=max(0, total_list - idx - 1),
                    )
                await asyncio.sleep(0.5)
                continue

            if vacancy.has_test:
                await db.upsert_vacancy_skip_or_test(
                    chat_id=chat_id,
                    vacancy_id=vacancy.id,
                    title=vacancy.name,
                    employer=vacancy.employer.name,
                    url=vacancy.vacancy_url,
                    salary_text=vacancy.salary_text,
                    status=VacancyStatus.REQUIRES_TEST,
                )
                processed += 1
                if processed % heartbeat_n == 0:
                    _heartbeat(
                        chat_id=chat_id,
                        processed=processed,
                        applied=run_applied,
                        failed=run_failed_perm,
                        remaining=max(0, total_list - idx - 1),
                    )
                await asyncio.sleep(0.5)
                continue

            reason, attempt_count = await db.try_claim_vacancy_for_processing(
                chat_id=chat_id,
                vacancy_id=vacancy.id,
                title=vacancy.name,
                employer=vacancy.employer.name,
                url=vacancy.vacancy_url,
                salary_text=vacancy.salary_text,
                retention_days=retention,
                lease_minutes=lease_min,
            )

            if reason == ClaimReason.SKIP_TERMINAL:
                processed += 1
                await asyncio.sleep(0.5)
                continue

            if reason == ClaimReason.SKIP_BACKOFF:
                log.info(
                    "pipeline.vacancy.skipped_due_to_backoff",
                    vacancy_id=vacancy.id,
                    title=vacancy.name,
                    chat_id=chat_id,
                    attempt_count=attempt_count,
                )
                processed += 1
                if processed % heartbeat_n == 0:
                    _heartbeat(
                        chat_id=chat_id,
                        processed=processed,
                        applied=run_applied,
                        failed=run_failed_perm,
                        remaining=max(0, total_list - idx - 1),
                    )
                await asyncio.sleep(0.5)
                continue

            if reason == ClaimReason.SKIP_IN_PROGRESS:
                processed += 1
                await asyncio.sleep(0.5)
                continue

            log.info(
                "pipeline.vacancy.start",
                vacancy_id=vacancy.id,
                title=vacancy.name,
                chat_id=chat_id,
                status=VacancyStatus.IN_PROGRESS.value,
                attempt_count=attempt_count,
            )

            letter = render_cover_letter(
                cover_letter,
                title=vacancy.name,
                employer=vacancy.employer.name,
            )

            outcome: ApplyStatus | None = None
            final_status = VacancyStatus.APPLY_PERM_ERROR
            last_err: str | None = None
            next_retry = None
            retryable_flag = False
            http_st: int | None = None

            try:
                apply_out = await asyncio.wait_for(
                    hh.apply(
                        vacancy_id=vacancy.id,
                        resume_id=resume_id,
                        letter=letter,
                        per_attempt_timeout=per_attempt,
                    ),
                    timeout=apply_total,
                )
                outcome = apply_out.status
                http_st = apply_out.http_status
                retryable_flag = apply_out.retryable
                last_err = apply_out.error_message or apply_out.error_code

                if apply_out.status == ApplyStatus.APPLIED:
                    final_status = VacancyStatus.APPLIED
                    run_applied += 1
                    result["applied"] += 1
                elif apply_out.status == ApplyStatus.ALREADY_APPLIED:
                    final_status = VacancyStatus.ALREADY_APPLIED
                elif apply_out.status == ApplyStatus.TIMEOUT:
                    final_status = VacancyStatus.APPLY_TIMEOUT
                    next_retry = db.compute_next_retry_at(attempt_count)
                    log.warning(
                        "pipeline.vacancy.timeout",
                        vacancy_id=vacancy.id,
                        title=vacancy.name,
                        chat_id=chat_id,
                        attempt_count=attempt_count,
                        http_status=http_st,
                    )
                    log.info(
                        "pipeline.vacancy.retry_scheduled",
                        vacancy_id=vacancy.id,
                        chat_id=chat_id,
                        next_retry_at=next_retry.isoformat(),
                        status=final_status.value,
                    )
                elif apply_out.status == ApplyStatus.TEMP_ERROR:
                    final_status = VacancyStatus.APPLY_TEMP_ERROR
                    next_retry = db.compute_next_retry_at(attempt_count)
                    log.info(
                        "pipeline.vacancy.retry_scheduled",
                        vacancy_id=vacancy.id,
                        chat_id=chat_id,
                        next_retry_at=next_retry.isoformat(),
                        retryable=True,
                        http_status=http_st,
                    )
                else:
                    final_status = VacancyStatus.APPLY_PERM_ERROR
                    run_failed_perm += 1

            except asyncio.TimeoutError:
                final_status = VacancyStatus.APPLY_TIMEOUT
                last_err = f"pipeline_wait_for_{apply_total}s"
                next_retry = db.compute_next_retry_at(attempt_count)
                log.warning(
                    "pipeline.vacancy.timeout",
                    vacancy_id=vacancy.id,
                    title=vacancy.name,
                    chat_id=chat_id,
                    attempt_count=attempt_count,
                    http_status=None,
                )
                log.info(
                    "pipeline.vacancy.retry_scheduled",
                    vacancy_id=vacancy.id,
                    chat_id=chat_id,
                    next_retry_at=next_retry.isoformat(),
                    status=final_status.value,
                )
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                final_status = VacancyStatus.APPLY_TEMP_ERROR
                last_err = str(exc)
                next_retry = db.compute_next_retry_at(attempt_count)
                log.exception(
                    "pipeline.vacancy.error",
                    vacancy_id=vacancy.id,
                    title=vacancy.name,
                    chat_id=chat_id,
                    error=str(exc),
                )
                log.info(
                    "pipeline.vacancy.retry_scheduled",
                    vacancy_id=vacancy.id,
                    chat_id=chat_id,
                    next_retry_at=next_retry.isoformat(),
                    status=final_status.value,
                )

            await db.persist_terminal_vacancy(
                chat_id=chat_id,
                vacancy_id=vacancy.id,
                status=final_status,
                last_error=last_err,
                next_retry_at=next_retry,
            )

            log.info(
                "pipeline.vacancy.finish",
                vacancy_id=vacancy.id,
                title=vacancy.name,
                chat_id=chat_id,
                status=final_status.value,
                attempt_count=attempt_count,
                retryable=retryable_flag,
                next_retry_at=next_retry.isoformat() if next_retry else None,
                http_status=http_st,
                outcome=outcome.value if outcome else None,
            )

            processed += 1
            if processed % heartbeat_n == 0:
                _heartbeat(
                    chat_id=chat_id,
                    processed=processed,
                    applied=run_applied,
                    failed=run_failed_perm,
                    remaining=max(0, total_list - idx - 1),
                )

            await asyncio.sleep(0.5)

    return result


def _heartbeat(
    *,
    chat_id: int,
    processed: int,
    applied: int,
    failed: int,
    remaining: int,
) -> None:
    log.info(
        "pipeline.heartbeat",
        chat_id=chat_id,
        processed_count=processed,
        applied_count=applied,
        failed_count=failed,
        remaining_count=remaining,
    )


async def _collect_vacancies(hh: HHClient, keywords: list[str], config) -> list[VacancySchema]:
    vacancies: list[VacancySchema] = []
    seen_ids: set[str] = set()

    for keyword in keywords:
        try:
            fetched = await hh.search_all(
                text=keyword,
                area=config.hh.search.area,
                schedule=config.hh.search.schedule or None,
                employment=config.hh.search.employment or None,
                search_field=config.hh.search.search_field or None,
                period=max(1, config.hh.search.published_within_hours // 24),
                max_vacancies=config.hh.search.max_vacancies_per_run,
            )
            added = 0
            for v in fetched:
                if v.id not in seen_ids and v.matches_keywords(keywords):
                    seen_ids.add(v.id)
                    vacancies.append(v)
                    added += 1
            log.info("pipeline.keyword.done", keyword=keyword, fetched=len(fetched), added=added)
        except Exception as exc:
            log.exception("pipeline.keyword.error", keyword=keyword, error=str(exc))

    return vacancies[: config.hh.search.max_vacancies_per_run]
