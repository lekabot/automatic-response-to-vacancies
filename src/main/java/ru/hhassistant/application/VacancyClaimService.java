package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.domain.model.UserSearchConfig;
import ru.hhassistant.domain.model.VacancyCandidate;
import ru.hhassistant.domain.model.VacancyDecision;
import ru.hhassistant.domain.model.VacancyStatus;
import ru.hhassistant.domain.port.VacancyRepository;

import java.time.Instant;

@ApplicationScoped
@Slf4j
public class VacancyClaimService {
  @Inject
  VacancyRepository vacancyRepository;
  @Inject
  MeterRegistry meterRegistry;

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
      case VacancyDecision.Claimed c -> log.info("vacancy.claimed chatId={} vacancyId={} attempt={}",
        config.chatId(), candidate.vacancyId(), c.attemptCount());
      case VacancyDecision.SkipTerminal t -> {
        meterRegistry.counter("hh.claim.skip_terminal",
          "status", t.currentStatus().name()).increment();
        log.debug("vacancy.skip_terminal chatId={} vacancyId={} status={}",
          config.chatId(), candidate.vacancyId(), t.currentStatus());
      }
      case VacancyDecision.SkipBackoff b -> {
        meterRegistry.counter("hh.claim.skip_backoff").increment();
        log.debug("vacancy.skip_backoff chatId={} vacancyId={} nextRetry={}",
          config.chatId(), candidate.vacancyId(), b.nextRetryAt());
      }
      case VacancyDecision.SkipInProgress p -> {
        meterRegistry.counter("hh.claim.skip_in_progress").increment();
        log.debug("vacancy.skip_in_progress chatId={} vacancyId={} leaseExpires={}",
          config.chatId(), candidate.vacancyId(), p.leaseExpiresAt());
      }
    }
    return decision;
  }

  public void recordSkipped(VacancyCandidate candidate, UserSearchConfig config, VacancyStatus status, Instant now) {
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
