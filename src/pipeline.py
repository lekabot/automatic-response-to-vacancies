"""
Пайплайн поиска вакансий и автоматических откликов (изолированно по chat_id).

Статусная модель: claim IN_PROGRESS → apply → финальный статус.
Волны поиска в одном run_user_pipeline до лимита / стопа / пустого repeat=0.
"""
from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, TypedDict

import structlog

from src import database as db
from src.config import get_config
from src.database import ClaimReason
from src.hh.apply_types import ApplyStatus
from src.hh.client import HHClient
from src.hh.relevance import is_vacancy_relevant_to_query
from src.hh.schemas import VacancySchema
from src.hh.session_status import SessionValidationStatus
from src.models import VacancyStatus

log = structlog.get_logger(__name__)

_PLACEHOLDER_RE = re.compile(r"\{([^{}]*)\}")

# Тесты: вызвать после тела волны (перед summary), можно бросить исключение
_after_wave_test_hook: Callable[[int, int], Awaitable[None]] | None = None


@dataclass
class WaveStats:
    collected_total: int = 0
    skipped_terminal: int = 0
    skipped_backoff: int = 0
    skipped_in_progress: int = 0
    skipped_requires_test: int = 0
    skipped_exclude: int = 0
    apply_attempted: int = 0
    apply_success: int = 0
    apply_already_applied: int = 0
    apply_temp_error: int = 0
    apply_timeout: int = 0
    apply_perm_error: int = 0
    terminal_breakdown: dict[str, int] = field(default_factory=dict)

    def record_terminal_skip(self, status: str) -> None:
        self.skipped_terminal += 1
        self.terminal_breakdown[status] = self.terminal_breakdown.get(status, 0) + 1


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


def _aware_dt(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt


async def _maybe_send_hourly_report(bot: Any, chat_id: int, daily_limit: int) -> None:
    if bot is None:
        return
    today_count = await db.get_applied_today_count(chat_id)
    if today_count >= daily_limit:
        return
    us = await db.get_user_settings(chat_id)
    if not us or not us.search_session_started_at:
        return
    now = datetime.now(timezone.utc)
    start = _aware_dt(us.search_session_started_at)
    current_slot = int((now - start).total_seconds() // 3600)
    if current_slot < 1:
        return
    slot, previous_last = await db.try_claim_current_hourly_report_slot(chat_id, now)
    if slot is None:
        return
    log.info(
        "hourly_report.current_slot_due",
        chat_id=chat_id,
        slot=slot,
        previous_last=previous_last,
    )
    try:
        from src.bot.formatters import format_session_progress_report

        stats = await db.get_session_window_stats(chat_id)
        if stats is None:
            stats = await db.get_today_stats(chat_id)
        test_rows, test_total = await db.get_session_test_vacancies(chat_id, limit=20)
        log.info(
            "hourly_report.test_vacancies_count",
            chat_id=chat_id,
            shown=len(test_rows),
            total=test_total,
        )
        text = format_session_progress_report(
            stats, test_rows, test_total, daily_limit, is_final=False
        )
        await bot.send_message(
            chat_id=chat_id,
            text=text,
            parse_mode="HTML",
            disable_web_page_preview=True,
        )
        log.info("hourly_report.sent", chat_id=chat_id, slot=slot)
        log.info("search.continues_after_hourly_report", chat_id=chat_id, slot=slot)
    except Exception:
        log.exception("hourly_report.send_failed", chat_id=chat_id, slot=slot)
        await db.revert_hourly_report_to_previous_last(chat_id, previous_last)


async def _send_final_limit_report(
    bot: Any,
    chat_id: int,
    daily_limit: int,
    *,
    final_sent: list[bool],
    result: PipelineResult,
) -> None:
    if final_sent[0]:
        result["stopped_by_limit"] = True
        return
    final_sent[0] = True
    result["stopped_by_limit"] = True
    stats = await db.get_session_window_stats(chat_id)
    if stats is None:
        stats = await db.get_today_stats(chat_id)
    test_rows, test_total = await db.get_session_test_vacancies(chat_id, limit=20)
    if bot is not None:
        try:
            from src.bot.formatters import format_session_progress_report

            text = format_session_progress_report(
                stats, test_rows, test_total, daily_limit, is_final=True
            )
            await bot.send_message(
                chat_id=chat_id,
                text=text,
                parse_mode="HTML",
                disable_web_page_preview=True,
            )
        except Exception:
            log.exception("pipeline.final_report_send_failed", chat_id=chat_id)
    await db.clear_search_session(chat_id)
    log.info(
        "pipeline.daily_limit_reached",
        limit=daily_limit,
        chat_id=chat_id,
        final_report=True,
    )


async def _idle_between_waves(
    bot: Any,
    chat_id: int,
    daily_limit: int,
    cancel_event: asyncio.Event,
    sleep_seconds: float,
    *,
    repeat_interval_minutes: float,
    next_wave_number: int,
    current_wave: int,
) -> bool:
    """True если пользователь отменил. Логи idle + heartbeat."""
    log.info(
        "pipeline.idle.before_next_wave",
        chat_id=chat_id,
        sleep_seconds=round(sleep_seconds, 1),
        repeat_interval_minutes=repeat_interval_minutes,
        next_wave_number=next_wave_number,
        current_wave=current_wave,
    )
    loop = asyncio.get_event_loop()
    deadline = loop.time() + sleep_seconds
    last_hb = loop.time()
    hb_interval = 60.0
    while True:
        if cancel_event.is_set():
            log.info(
                "pipeline.idle.wakeup",
                chat_id=chat_id,
                reason="user_cancel",
                next_wave_number=next_wave_number,
            )
            return True
        phase_hb = "hourly_report_check"
        try:
            await _maybe_send_hourly_report(bot, chat_id, daily_limit)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception(
                "pipeline.idle.hourly_check_failed",
                chat_id=chat_id,
                phase=phase_hb,
            )
        remaining = deadline - loop.time()
        if remaining <= 0:
            log.info(
                "pipeline.idle.wakeup",
                chat_id=chat_id,
                reason="sleep_complete",
                next_wave_number=next_wave_number,
            )
            return False
        now = loop.time()
        if sleep_seconds > 30 and (now - last_hb) >= hb_interval:
            us = await db.get_user_settings(chat_id)
            session_active = bool(us and us.search_session_started_at is not None)
            slot = us.last_hourly_report_slot if us else None
            log.info(
                "pipeline.idle.heartbeat",
                chat_id=chat_id,
                session_active=session_active,
                seconds_remaining=round(max(0.0, remaining), 1),
                current_wave=current_wave,
                last_hourly_report_slot=slot,
            )
            last_hb = now
        chunk = min(60.0, remaining)
        try:
            await asyncio.wait_for(cancel_event.wait(), timeout=chunk)
            log.info(
                "pipeline.idle.wakeup",
                chat_id=chat_id,
                reason="user_cancel",
                next_wave_number=next_wave_number,
            )
            return True
        except asyncio.TimeoutError:
            pass


def _wave_next_action(
    *,
    cancel: bool,
    stopped_limit: bool,
    repeat_minutes: float,
    collected_empty: bool,
) -> str:
    if stopped_limit:
        return "stop_limit"
    if cancel:
        return "stop_user"
    if repeat_minutes > 0:
        return "sleep"
    return "complete"


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
    bot: Any | None = None,
) -> PipelineResult:
    config = get_config()
    result: PipelineResult = {
        "applied": 0,
        "stopped_by_limit": False,
        "hh_temp_unavailable": False,
        "session_invalid": False,
    }
    final_sent: list[bool] = [False]
    lease_min = config.hh.search.vacancy_lease_minutes
    heartbeat_n = max(1, config.hh.search.pipeline_heartbeat_every)
    apply_total = config.hh.search.apply_total_timeout_seconds
    per_attempt = config.hh.search.apply_per_attempt_timeout_seconds
    retention = config.storage.retention_days
    daily_limit = config.hh.search.daily_apply_limit
    repeat_minutes = float(config.hh.search.repeat_interval_minutes or 0)

    async def load_wave() -> tuple[str, list[str], str] | None:
        us = await db.get_user_settings(chat_id)
        if us and us.resume_id and us.keywords:
            return us.resume_id, list(us.keywords), us.cover_letter or ""
        if resume_id and keywords:
            return resume_id, list(keywords), cover_letter or ""
        return None

    wave = 0
    phase = "init"
    main_loop_ran = False
    no_settings_abort = False

    try:
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
                    log.info("pipeline.search.stopped_by_auth", chat_id=chat_id, reason="session_invalid")
                    return result
                if st is SessionValidationStatus.TEMP_UNAVAILABLE:
                    log.warning(
                        "pipeline.hh_temp_unavailable_skip_cycle",
                        chat_id=chat_id,
                    )
                    result["hh_temp_unavailable"] = True
                    log.info(
                        "pipeline.search.stopped_by_auth",
                        chat_id=chat_id,
                        reason="hh_temp_unavailable",
                    )
                    return result
                logged_in = True
            else:
                logged_in = await hh.login(hh_email, hh_password or "")
            if not logged_in:
                log.error("pipeline.login_failed", chat_id=chat_id)
                log.info("pipeline.search.stopped_by_auth", chat_id=chat_id, reason="login_failed")
                return result

            while not cancel_event.is_set() and not result["stopped_by_limit"]:
                main_loop_ran = True
                wave += 1
                next_wave_num = wave + 1
                log.info(
                    "pipeline.wave.start",
                    chat_id=chat_id,
                    wave=wave,
                    next_wave_number=next_wave_num,
                )
                phase = "collect"
                loaded = await load_wave()
                if not loaded:
                    no_settings_abort = True
                    log.warning("pipeline.no_settings_abort", chat_id=chat_id)
                    log.info(
                        "pipeline.wave.summary",
                        chat_id=chat_id,
                        wave=wave,
                        collected_total=0,
                        skipped_terminal=0,
                        skipped_backoff=0,
                        skipped_in_progress=0,
                        skipped_requires_test=0,
                        skipped_exclude=0,
                        apply_attempted=0,
                        apply_success=0,
                        apply_already_applied=0,
                        apply_temp_error=0,
                        apply_timeout=0,
                        apply_perm_error=0,
                        terminal_only_wave=False,
                        next_action="stop_error",
                    )
                    log.info(
                        "pipeline.wave.finished",
                        chat_id=chat_id,
                        wave=wave,
                        next_action="stop_error",
                    )
                    break
                rid, kws, cletter = loaded

                vacancies = await _collect_vacancies(hh, kws, config)
                ws = WaveStats()
                ws.collected_total = len(vacancies)
                total_list = ws.collected_total
                log.info(
                    "pipeline.collected",
                    total=total_list,
                    chat_id=chat_id,
                    wave=wave,
                )

                phase = "process_vacancies"
                processed = 0
                run_applied = 0
                run_failed_perm = 0

                for idx, vacancy in enumerate(vacancies):
                    if cancel_event.is_set():
                        break

                    today_count = await db.get_applied_today_count(chat_id)
                    if today_count >= daily_limit:
                        phase = "finalize"
                        await _send_final_limit_report(
                            bot, chat_id, daily_limit, final_sent=final_sent, result=result
                        )
                        break

                    phase = "hourly_report_check"
                    await _maybe_send_hourly_report(bot, chat_id, daily_limit)
                    phase = "process_vacancies"
                    if cancel_event.is_set():
                        break
                    if result["stopped_by_limit"]:
                        break

                    if vacancy.matches_exclude(config.hh.search.exclude_keywords):
                        ws.skipped_exclude += 1
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
                        ws.skipped_requires_test += 1
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

                    claim = await db.try_claim_vacancy_for_processing(
                        chat_id=chat_id,
                        vacancy_id=vacancy.id,
                        title=vacancy.name,
                        employer=vacancy.employer.name,
                        url=vacancy.vacancy_url,
                        salary_text=vacancy.salary_text,
                        retention_days=retention,
                        lease_minutes=lease_min,
                    )
                    attempt_count = claim.attempt_count
                    _nr = (
                        claim.next_retry_at.isoformat()
                        if claim.next_retry_at is not None
                        else None
                    )
                    _st = claim.current_status.value if claim.current_status is not None else None

                    if claim.reason == ClaimReason.SKIP_TERMINAL:
                        if _st:
                            ws.record_terminal_skip(_st)
                        log.info(
                            "pipeline.vacancy.skipped_terminal",
                            chat_id=chat_id,
                            vacancy_id=vacancy.id,
                            title=vacancy.name,
                            attempt_count=attempt_count,
                            current_status=_st,
                            next_retry_at=_nr,
                        )
                        processed += 1
                        await asyncio.sleep(0.5)
                        continue

                    if claim.reason == ClaimReason.SKIP_BACKOFF:
                        ws.skipped_backoff += 1
                        log.info(
                            "pipeline.vacancy.skipped_due_to_backoff",
                            chat_id=chat_id,
                            vacancy_id=vacancy.id,
                            title=vacancy.name,
                            attempt_count=attempt_count,
                            current_status=_st,
                            next_retry_at=_nr,
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

                    if claim.reason == ClaimReason.SKIP_IN_PROGRESS:
                        ws.skipped_in_progress += 1
                        log.info(
                            "pipeline.vacancy.skipped_in_progress",
                            chat_id=chat_id,
                            vacancy_id=vacancy.id,
                            title=vacancy.name,
                            attempt_count=attempt_count,
                            current_status=_st,
                            next_retry_at=_nr,
                        )
                        processed += 1
                        await asyncio.sleep(0.5)
                        continue

                    log.info(
                        "pipeline.vacancy.claimed",
                        chat_id=chat_id,
                        vacancy_id=vacancy.id,
                        title=vacancy.name,
                        attempt_count=attempt_count,
                        current_status=_st,
                        next_retry_at=_nr,
                    )
                    log.info(
                        "pipeline.vacancy.start",
                        vacancy_id=vacancy.id,
                        title=vacancy.name,
                        chat_id=chat_id,
                        status=VacancyStatus.IN_PROGRESS.value,
                        attempt_count=attempt_count,
                    )

                    letter = render_cover_letter(
                        cletter,
                        title=vacancy.name,
                        employer=vacancy.employer.name,
                    )

                    ws.apply_attempted += 1
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
                                resume_id=rid,
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
                            ws.apply_success += 1
                            run_applied += 1
                            result["applied"] += 1
                        elif apply_out.status == ApplyStatus.ALREADY_APPLIED:
                            final_status = VacancyStatus.ALREADY_APPLIED
                            ws.apply_already_applied += 1
                        elif apply_out.status == ApplyStatus.TIMEOUT:
                            final_status = VacancyStatus.APPLY_TIMEOUT
                            ws.apply_timeout += 1
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
                            ws.apply_temp_error += 1
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
                            ws.apply_perm_error += 1
                            run_failed_perm += 1

                    except asyncio.TimeoutError:
                        ws.apply_timeout += 1
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
                        ws.apply_temp_error += 1
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

                    if outcome == ApplyStatus.APPLIED:
                        tc = await db.get_applied_today_count(chat_id)
                        if tc >= daily_limit:
                            phase = "finalize"
                            await _send_final_limit_report(
                                bot,
                                chat_id,
                                daily_limit,
                                final_sent=final_sent,
                                result=result,
                            )
                            break

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

                phase = "wave_summary"
                if _after_wave_test_hook is not None:
                    await _after_wave_test_hook(wave, chat_id)

                cancel = cancel_event.is_set()
                stopped = result["stopped_by_limit"]
                terminal_only_wave = (
                    ws.collected_total > 0
                    and ws.apply_attempted == 0
                    and ws.skipped_backoff == 0
                    and ws.skipped_in_progress == 0
                )
                next_action = _wave_next_action(
                    cancel=cancel,
                    stopped_limit=stopped,
                    repeat_minutes=repeat_minutes,
                    collected_empty=ws.collected_total == 0,
                )
                if stopped:
                    next_action = "stop_limit"
                elif cancel:
                    next_action = "stop_user"

                log.info(
                    "pipeline.wave.summary",
                    chat_id=chat_id,
                    wave=wave,
                    collected_total=ws.collected_total,
                    skipped_terminal=ws.skipped_terminal,
                    skipped_backoff=ws.skipped_backoff,
                    skipped_in_progress=ws.skipped_in_progress,
                    skipped_requires_test=ws.skipped_requires_test,
                    skipped_exclude=ws.skipped_exclude,
                    apply_attempted=ws.apply_attempted,
                    apply_success=ws.apply_success,
                    apply_already_applied=ws.apply_already_applied,
                    apply_temp_error=ws.apply_temp_error,
                    apply_timeout=ws.apply_timeout,
                    apply_perm_error=ws.apply_perm_error,
                    terminal_only_wave=terminal_only_wave,
                    next_action=next_action,
                )
                if terminal_only_wave:
                    breakdown: dict[str, int] = dict(ws.terminal_breakdown)
                    if ws.skipped_requires_test:
                        breakdown["REQUIRES_TEST"] = breakdown.get("REQUIRES_TEST", 0) + ws.skipped_requires_test
                    if ws.skipped_exclude:
                        breakdown["SKIPPED_EXCLUDE_KEYWORD"] = (
                            breakdown.get("SKIPPED_EXCLUDE_KEYWORD", 0) + ws.skipped_exclude
                        )
                    log.info(
                        "pipeline.wave.no_actionable_vacancies",
                        chat_id=chat_id,
                        wave=wave,
                        collected_total=ws.collected_total,
                        breakdown=breakdown,
                        next_action=next_action,
                    )

                log.info(
                    "pipeline.wave.finished",
                    chat_id=chat_id,
                    wave=wave,
                    next_action=next_action,
                )

                if cancel or stopped:
                    break

                if ws.collected_total == 0:
                    if repeat_minutes > 0:
                        phase = "sleep"
                        cancelled = await _idle_between_waves(
                            bot,
                            chat_id,
                            daily_limit,
                            cancel_event,
                            repeat_minutes * 60,
                            repeat_interval_minutes=repeat_minutes,
                            next_wave_number=next_wave_num,
                            current_wave=wave,
                        )
                        if cancelled:
                            break
                        continue
                    break

                if repeat_minutes > 0:
                    phase = "sleep"
                    cancelled = await _idle_between_waves(
                        bot,
                        chat_id,
                        daily_limit,
                        cancel_event,
                        repeat_minutes * 60,
                        repeat_interval_minutes=repeat_minutes,
                        next_wave_number=next_wave_num,
                        current_wave=wave,
                    )
                    if cancelled:
                        break
                    continue
                break

    except asyncio.CancelledError:
        raise
    except BaseException as exc:
        log.exception(
            "pipeline.search.crashed",
            chat_id=chat_id,
            wave=wave,
            phase=phase,
            error=str(exc),
        )
        raise

    if result["stopped_by_limit"]:
        log.info("pipeline.search.stopped_by_limit", chat_id=chat_id, last_wave=wave)
    elif cancel_event.is_set():
        log.info("pipeline.search.stopped_by_user", chat_id=chat_id, last_wave=wave)
    elif (
        main_loop_ran
        and not no_settings_abort
        and not result["session_invalid"]
        and not result["hh_temp_unavailable"]
    ):
        log.info("pipeline.search.completed", chat_id=chat_id, last_wave=wave)

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
                if v.id in seen_ids:
                    continue
                ok, matched, missing, miss_lang = is_vacancy_relevant_to_query(keyword, v)
                if not ok:
                    log.info(
                        "search.filtered_irrelevant",
                        query=keyword,
                        vacancy_id=v.id,
                        title=(v.name or "")[:120],
                        matched_tokens=matched,
                        missing_tokens=missing,
                        missing_required_language_tokens=miss_lang,
                    )
                    continue
                seen_ids.add(v.id)
                vacancies.append(v)
                added += 1
            log.info("pipeline.keyword.done", keyword=keyword, fetched=len(fetched), added=added)
        except Exception as exc:
            log.exception("pipeline.keyword.error", keyword=keyword, error=str(exc))

    return vacancies[: config.hh.search.max_vacancies_per_run]
