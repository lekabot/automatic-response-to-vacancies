package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import ru.hhassistant.domain.model.*;
import ru.hhassistant.domain.policy.RetryPolicy;
import ru.hhassistant.domain.port.VacancyRepository;
import ru.hhassistant.infrastructure.hh.ApplyOutcome;
import ru.hhassistant.infrastructure.hh.ApplyStatus;
import ru.hhassistant.infrastructure.hh.HhApplyClient;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Оркестрирует отклик на вакансию и финализирует состояние в БД.
 *
 * <p>Не содержит HTTP-логики — делегирует {@link HhApplyClient}.
 * Не содержит retry-политики — делегирует {@link RetryPolicy}.
 * Персистирует результат через {@link VacancyRepository}.
 */
@ApplicationScoped
public class VacancyApplyService {

    private static final Logger log = Logger.getLogger(VacancyApplyService.class);

    @Inject HhApplyClient applyClient;
    @Inject VacancyRepository vacancyRepository;
    @Inject RetryPolicy retryPolicy;
    @Inject MeterRegistry meterRegistry;
    @Inject Clock clock;

    /**
     * Выполняет отклик на вакансию и персистирует итоговый статус.
     *
     * @return итоговый {@link VacancyStatus} для логирования/метрик выше по стеку
     */
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
            log.errorf(ex, "apply.unexpected vacancyId=%s chatId=%d",
                candidate.vacancyId(), config.chatId());
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
            log.infof("vacancy.applied chatId=%d vacancyId=%s title='%s' attempt=%d",
                attempt.chatId(), attempt.vacancyId(), attempt.vacancyTitle(), attempt.attemptNumber());
        } else if (finalStatus.isRetryable()) {
            log.infof("vacancy.retry_scheduled chatId=%d vacancyId=%s status=%s nextRetry=%s attempt=%d",
                attempt.chatId(), attempt.vacancyId(), finalStatus, nextRetryAt, attempt.attemptNumber());
        } else {
            log.warnf("vacancy.perm_error chatId=%d vacancyId=%s status=%s errorCode=%s attempt=%d",
                attempt.chatId(), attempt.vacancyId(), finalStatus,
                outcome.errorCode(), attempt.attemptNumber());
        }
    }
}
