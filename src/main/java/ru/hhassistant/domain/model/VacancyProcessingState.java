package ru.hhassistant.domain.model;

import java.time.Instant;

/**
 * Персистируемое состояние обработки вакансии для конкретного пользователя (chatId).
 * Хранится в таблице vacancies_seen.
 */
public record VacancyProcessingState(
    long chatId,
    String vacancyId,
    String title,
    String employer,
    String url,
    String salaryText,
    VacancyStatus status,
    int attemptCount,
    String lastError,             // null если ошибки не было
    Instant lastAttemptAt,        // null для новых записей
    Instant nextRetryAt,          // null если retry не запланирован
    Instant processingStartedAt,  // null если не в процессе обработки
    Instant seenAt
) {

    /** Истёк ли lease IN_PROGRESS (processingStartedAt + leaseMinutes). */
    public boolean isLeaseExpired(int leaseMinutes, Instant now) {
        if (processingStartedAt == null) return true;
        return now.isAfter(processingStartedAt.plusSeconds((long) leaseMinutes * 60));
    }

    /** Доступен ли retry (nextRetryAt прошёл). */
    public boolean isReadyForRetry(Instant now) {
        if (nextRetryAt == null) return true;
        return !now.isBefore(nextRetryAt);
    }
}
