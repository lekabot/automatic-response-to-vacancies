package ru.hhassistant.domain.model;

import java.time.Instant;

/**
 * Результат попытки заклеймить вакансию для обработки.
 * Sealed hierarchy: компилятор гарантирует исчерпывающее сопоставление.
 */
public sealed interface VacancyDecision {

    /** Вакансия успешно заклеймирована, можно выполнять отклик. */
    record Claimed(int attemptCount) implements VacancyDecision {}

    /**
     * Вакансия находится в терминальном статусе — повтора не будет.
     * {@code currentStatus} позволяет наблюдать причину в логах и метриках.
     */
    record SkipTerminal(VacancyStatus currentStatus, int attemptCount) implements VacancyDecision {}

    /**
     * Вакансия ждёт backoff (APPLY_TIMEOUT / APPLY_TEMP_ERROR).
     * {@code nextRetryAt} определяет, когда можно попробовать снова.
     */
    record SkipBackoff(Instant nextRetryAt, int attemptCount, VacancyStatus currentStatus) implements VacancyDecision {}

    /**
     * Вакансия уже обрабатывается другим потоком/инстансом (IN_PROGRESS + активная lease).
     */
    record SkipInProgress(Instant leaseExpiresAt, int attemptCount) implements VacancyDecision {}
}
