package ru.hhassistant.domain.policy;

import java.time.Instant;

/**
 * Запланированный retry для конкретной вакансии.
 */
public record RetrySchedule(
    String vacancyId,
    Instant nextRetryAt,
    int attemptCount,
    String reason  // "timeout" | "temp_error" | "unexpected"
) {}
