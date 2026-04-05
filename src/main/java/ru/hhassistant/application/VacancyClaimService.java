package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import ru.hhassistant.domain.model.VacancyCandidate;
import ru.hhassistant.domain.model.VacancyDecision;
import ru.hhassistant.domain.model.UserSearchConfig;
import ru.hhassistant.domain.port.VacancyRepository;

import java.time.Instant;

/**
 * Атомарно клеймирует вакансии для обработки.
 * Инкапсулирует взаимодействие с репозиторием и метриками.
 */
@ApplicationScoped
public class VacancyClaimService {

    private static final Logger log = Logger.getLogger(VacancyClaimService.class);

    @Inject VacancyRepository vacancyRepository;
    @Inject MeterRegistry meterRegistry;

    /**
     * Пытается заклеймировать одну вакансию.
     *
     * @return sealed {@link VacancyDecision} — вызывающий код обрабатывает через switch
     */
    public VacancyDecision tryClaim(VacancyCandidate candidate, UserSearchConfig config, Instant now) {
        VacancyDecision decision = vacancyRepository.tryClaim(
            config.chatId(),
            candidate.vacancyId(),
            candidate.title(),
            candidate.employer(),
            candidate.url(),
            candidate.salaryText(),
            config.leaseMinutes(),
            config.retentionDays(),
            now
        );

        switch (decision) {
            case VacancyDecision.Claimed c ->
                log.infof("vacancy.claimed chatId=%d vacancyId=%s attempt=%d",
                    config.chatId(), candidate.vacancyId(), c.attemptCount());
            case VacancyDecision.SkipTerminal t -> {
                meterRegistry.counter("hh.claim.skip_terminal",
                    "status", t.currentStatus().name()).increment();
                log.debugf("vacancy.skip_terminal chatId=%d vacancyId=%s status=%s",
                    config.chatId(), candidate.vacancyId(), t.currentStatus());
            }
            case VacancyDecision.SkipBackoff b -> {
                meterRegistry.counter("hh.claim.skip_backoff").increment();
                log.debugf("vacancy.skip_backoff chatId=%d vacancyId=%s nextRetry=%s",
                    config.chatId(), candidate.vacancyId(), b.nextRetryAt());
            }
            case VacancyDecision.SkipInProgress p -> {
                meterRegistry.counter("hh.claim.skip_in_progress").increment();
                log.debugf("vacancy.skip_in_progress chatId=%d vacancyId=%s leaseExpires=%s",
                    config.chatId(), candidate.vacancyId(), p.leaseExpiresAt());
            }
        }
        return decision;
    }

    /**
     * Записывает SKIPPED или REQUIRES_TEST без попытки клейма.
     */
    public void recordSkipped(VacancyCandidate candidate, UserSearchConfig config,
                               ru.hhassistant.domain.model.VacancyStatus status, Instant now) {
        vacancyRepository.upsertSkipped(
            config.chatId(),
            candidate.vacancyId(),
            candidate.title(),
            candidate.employer(),
            candidate.url(),
            candidate.salaryText(),
            status,
            now
        );
        meterRegistry.counter("hh.vacancy.skipped", "reason", status.name()).increment();
    }
}
