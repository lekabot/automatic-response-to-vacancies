package ru.hhassistant.domain.model;

import java.time.Instant;

public sealed interface VacancyDecision {
  record Claimed(int attemptCount) implements VacancyDecision {
  }

  record SkipTerminal(VacancyStatus currentStatus, int attemptCount) implements VacancyDecision {
  }

  record SkipBackoff(Instant nextRetryAt, int attemptCount, VacancyStatus currentStatus) implements VacancyDecision {
  }

  record SkipInProgress(Instant leaseExpiresAt, int attemptCount) implements VacancyDecision {
  }
}
