package ru.hhassistant.domain.model;

import java.time.Instant;

public record VacancyProcessingState(
  long chatId,
  String vacancyId,
  String title,
  String employer,
  String url,
  String salaryText,
  VacancyStatus status,
  int attemptCount,
  String lastError,             // null has no error
  Instant lastAttemptAt,        // null for new record
  Instant nextRetryAt,          // null if retry is not planed
  Instant processingStartedAt,  // null if it is not processing handling
  Instant seenAt
) {

  public boolean isLeaseExpired(int leaseMinutes, Instant now) {
    if (processingStartedAt == null) return true;
    return now.isAfter(processingStartedAt.plusSeconds((long) leaseMinutes * 60));
  }

  public boolean isReadyForRetry(Instant now) {
    if (nextRetryAt == null) return true;
    return !now.isBefore(nextRetryAt);
  }
}
