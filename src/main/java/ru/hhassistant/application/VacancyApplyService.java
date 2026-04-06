package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.domain.model.ApplicationAttempt;
import ru.hhassistant.domain.model.UserSearchConfig;
import ru.hhassistant.domain.model.VacancyCandidate;
import ru.hhassistant.domain.model.VacancyStatus;
import ru.hhassistant.domain.policy.RetryPolicy;
import ru.hhassistant.domain.port.VacancyRepository;
import ru.hhassistant.infrastructure.hh.ApplyOutcome;
import ru.hhassistant.infrastructure.hh.ApplyStatus;
import ru.hhassistant.infrastructure.hh.HhApplyClient;

import java.time.Clock;
import java.time.Instant;

@ApplicationScoped
@Slf4j
public class VacancyApplyService {
  @Inject
  HhApplyClient applyClient;
  @Inject
  VacancyRepository vacancyRepository;
  @Inject
  RetryPolicy retryPolicy;
  @Inject
  MeterRegistry meterRegistry;
  @Inject
  Clock clock;

  public VacancyStatus applyAndPersist(
    VacancyCandidate candidate,
    UserSearchConfig config,
    int attemptCount,
    String hhtoken
  ) {
    Instant now = clock.instant();
    String coverLetter = CoverLetterRenderer.render(
      config.coverLetterTemplate(),
      candidate.title(),
      candidate.employer()
    );

    ApplicationAttempt attempt = new ApplicationAttempt(
      config.chatId(),
      candidate.vacancyId(),
      candidate.title(),
      candidate.employer(),
      config.resumeId(),
      coverLetter,
      attemptCount,
      now
    );

    Timer.Sample sample = Timer.start(meterRegistry);
    ApplyOutcome outcome;
    try {
      outcome = applyClient.apply(
        candidate.vacancyId(),
        config.resumeId(),
        coverLetter,
        hhtoken,
        config.applyTotalTimeoutSeconds(),
        config.applyPerAttemptTimeoutSeconds()
      );
    } catch (Exception ex) {
      log.error("apply.unexpected vacancyId={} chatId={}",
        candidate.vacancyId(), config.chatId(), ex);
      outcome = ApplyOutcome.tempError("unexpected_exception", ex.getMessage());
    }
    sample.stop(meterRegistry.timer("hh.apply.duration",
      "status", outcome.status().name()));

    VacancyStatus finalStatus = mapToVacancyStatus(outcome.status());
    Instant nextRetryAt = null;
    if (finalStatus.isRetryable()) {
      nextRetryAt = retryPolicy.computeNextRetryAt(attemptCount);
    }

    vacancyRepository.persistOutcome(
      config.chatId(),
      candidate.vacancyId(),
      finalStatus,
      outcome.errorCode(),
      nextRetryAt,
      clock.instant()
    );

    logResult(attempt, finalStatus, outcome, nextRetryAt);
    meterRegistry.counter("hh.apply.outcome", "status", finalStatus.name()).increment();

    return finalStatus;
  }

  // ─── private ─────────────────────────────────────────────────────────────

  private static VacancyStatus mapToVacancyStatus(ApplyStatus status) {
    return switch (status) {
      case APPLIED -> VacancyStatus.APPLIED;
      case ALREADY_APPLIED -> VacancyStatus.ALREADY_APPLIED;
      case TIMEOUT -> VacancyStatus.APPLY_TIMEOUT;
      case TEMP_ERROR -> VacancyStatus.APPLY_TEMP_ERROR;
      case PERM_ERROR, AUTH_ERROR -> VacancyStatus.APPLY_PERM_ERROR;
    };
  }

  private void logResult(ApplicationAttempt attempt, VacancyStatus finalStatus,
                         ApplyOutcome outcome, Instant nextRetryAt) {
    if (finalStatus == VacancyStatus.APPLIED) {
      log.info("vacancy.applied chatId={} vacancyId={} title='{}' attempt={}",
        attempt.chatId(), attempt.vacancyId(), attempt.vacancyTitle(), attempt.attemptNumber());
    } else if (finalStatus.isRetryable()) {
      log.info("vacancy.retry_scheduled chatId={} vacancyId={} status={} nextRetry={} attempt={}",
        attempt.chatId(), attempt.vacancyId(), finalStatus, nextRetryAt, attempt.attemptNumber());
    } else {
      log.warn("vacancy.perm_error chatId={} vacancyId={} status={} errorCode={} attempt={}",
        attempt.chatId(), attempt.vacancyId(), finalStatus,
        outcome.errorCode(), attempt.attemptNumber());
    }
  }
}
