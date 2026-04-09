package ru.hhassistant.domain.policy;

import java.time.Instant;

public record RetrySchedule(
  String vacancyId,
  Instant nextRetryAt,
  int attemptCount,
  String reason
) {
}
