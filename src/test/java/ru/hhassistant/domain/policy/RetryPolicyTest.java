package ru.hhassistant.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {
  private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

  RetryPolicy policyWithFakeClock() {
    return RetryPolicy.defaultPolicy(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void firstAttempt_retryAfterAtLeast60Seconds() {
    var policy = policyWithFakeClock();
    Instant nextRetry = policy.computeNextRetryAt(1);
    assertThat(nextRetry).isAfter(NOW.plusSeconds(60));
    assertThat(nextRetry).isBefore(NOW.plusSeconds(120)); // 60 + max jitter 30
  }

  @ParameterizedTest
  @CsvSource({
    "1, 60,  150",   // 60 * 2^0 + 30 jitter = 60..90
    "2, 120, 210",   // 60 * 2^1 = 120
    "3, 240, 330",   // 60 * 2^2 = 240
    "6, 1920, 2010", // capped at maxExp=6: 60 * 2^5 = 1920
    "7, 1920, 2010", // attemptCount > maxExp — same as 6
  })
  void retryDelay_growsExponentially(int attempt, long minSeconds, long maxSeconds) {
    var policy = policyWithFakeClock();
    Instant nextRetry = policy.computeNextRetryAt(attempt);
    assertThat(nextRetry).isAfterOrEqualTo(NOW.plusSeconds(minSeconds));
    assertThat(nextRetry).isBeforeOrEqualTo(NOW.plusSeconds(maxSeconds));
  }

  @Test
  void higherAttemptCount_producesLaterRetry_onAverage() {
    var policy = new RetryPolicy(60.0, 6, 0.0, Clock.fixed(NOW, ZoneOffset.UTC));
    // Jitter=0 → детерминировано
    Instant retry1 = policy.computeNextRetryAt(1);
    Instant retry2 = policy.computeNextRetryAt(2);
    Instant retry3 = policy.computeNextRetryAt(3);
    assertThat(retry2).isAfter(retry1);
    assertThat(retry3).isAfter(retry2);
  }

  @Test
  void scheduleFor_includesVacancyIdAndReason() {
    var policy = policyWithFakeClock();
    var schedule = policy.scheduleFor("vac123", 2, "temp_error");
    assertThat(schedule.vacancyId()).isEqualTo("vac123");
    assertThat(schedule.reason()).isEqualTo("temp_error");
    assertThat(schedule.attemptCount()).isEqualTo(2);
    assertThat(schedule.nextRetryAt()).isAfter(NOW);
  }
}
