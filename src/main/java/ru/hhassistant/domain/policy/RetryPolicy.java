package ru.hhassistant.domain.policy;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class RetryPolicy {
  private final double baseSeconds;
  private final int maxExponent;
  private final double jitterSeconds;
  private final Clock clock;

  public RetryPolicy(double baseSeconds, int maxExponent, double jitterSeconds, Clock clock) {
    this.baseSeconds = baseSeconds;
    this.maxExponent = maxExponent;
    this.jitterSeconds = jitterSeconds;
    this.clock = clock;
  }

  public static RetryPolicy defaultPolicy(Clock clock) {
    return new RetryPolicy(60.0, 6, 30.0, clock);
  }

  public Instant computeNextRetryAt(int attemptCount) {
    int exp = Math.max(1, Math.min(attemptCount, maxExponent));
    double jitter = jitterSeconds > 0.0 ? ThreadLocalRandom.current().nextDouble(0.0, jitterSeconds) : 0.0;
    double delay = baseSeconds * Math.pow(2.0, exp - 1) + jitter;
    long delayMillis = (long) (delay * 1000);
    return clock.instant().plusMillis(delayMillis);
  }

  public RetrySchedule scheduleFor(String vacancyId, int attemptCount, String reason) {
    return new RetrySchedule(vacancyId, computeNextRetryAt(attemptCount), attemptCount, reason);
  }
}
