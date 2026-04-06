package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.config.StorageConfig;
import ru.hhassistant.domain.model.*;
import ru.hhassistant.domain.port.NotificationPort;
import ru.hhassistant.domain.port.SearchSessionRepository;
import ru.hhassistant.domain.port.UserSettingsRepository;
import ru.hhassistant.domain.port.VacancyRepository;
import ru.hhassistant.infrastructure.hh.HhPublicApiClient;
import ru.hhassistant.infrastructure.hh.HhSessionValidator;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class SearchSessionService {
  @Inject
  HhPublicApiClient hhApiClient;
  @Inject
  HhSessionValidator sessionValidator;
  @Inject
  VacancyFilterService filterService;
  @Inject
  VacancyClaimService claimService;
  @Inject
  VacancyApplyService applyService;
  @Inject
  VacancyStateService stateService;
  @Inject
  HourlyReportService hourlyReportService;
  @Inject
  FinalReportService finalReportService;
  @Inject
  UserSettingsRepository userSettingsRepository;
  @Inject
  SearchSessionRepository sessionRepository;
  @Inject
  VacancyRepository vacancyRepository;
  @Inject
  HhConfig hhConfig;
  @Inject
  StorageConfig storageConfig;
  @Inject
  MeterRegistry meterRegistry;
  @Inject
  Clock clock;
  @Inject
  NotificationPort notificationPort;

  public enum CycleOutcome {
    COMPLETED,
    DAILY_LIMIT_REACHED,
    SESSION_INVALID,
    HH_TEMP_UNAVAILABLE,
    CONFIG_MISSING
  }

  public CycleOutcome executeCycle(SearchSession session) {
    long chatId = session.chatId();

    Optional<UserSearchConfig> configOpt = buildUserSearchConfig(chatId);
    if (configOpt.isEmpty()) {
      log.warn("polling_cycle.config_missing chatId={}", chatId);
      return CycleOutcome.CONFIG_MISSING;
    }
    UserSearchConfig config = configOpt.get();
    if (!config.isComplete()) {
      log.warn("polling_cycle.config_incomplete chatId={}", chatId);
      return CycleOutcome.CONFIG_MISSING;
    }

    String hhtoken = getHhtoken(config);
    SessionValidationResult validation = sessionValidator.validate(hhtoken);
    switch (validation) {
      case INVALID -> {
        log.error("polling_cycle.session_invalid chatId={}", chatId);
        notificationPort.sendSessionInvalidWarning(chatId);
        return CycleOutcome.SESSION_INVALID;
      }
      case TEMP_UNAVAILABLE -> {
        log.warn("polling_cycle.hh_temp_unavailable chatId={}", chatId);
        return CycleOutcome.HH_TEMP_UNAVAILABLE;
      }
      case VALID -> {
      }
    }

    log.info("polling_cycle.start chatId={} keywords={}", chatId, config.keywords().size());
    meterRegistry.counter("hh.polling_cycle.started").increment();

    Map<String, List<VacancyCandidate>> byKeyword = fetchVacancies(config);
    List<VacancyCandidate> candidates = filterService.mergeAndFilter(byKeyword, config);

    log.info("polling_cycle.candidates chatId={} count={}", chatId, candidates.size());
    meterRegistry.gauge("hh.polling_cycle.candidates", candidates.size());

    for (VacancyCandidate candidate : candidates) {
      if (stateService.countAppliedToday(chatId) >= config.dailyApplyLimit()) {
        log.info("polling_cycle.daily_limit_reached chatId={} limit={}",
          chatId, config.dailyApplyLimit());
        finalReportService.sendFinalAndClearSession(session, config.dailyApplyLimit());
        return CycleOutcome.DAILY_LIMIT_REACHED;
      }

      processOneVacancy(candidate, config, hhtoken, session);

      hourlyReportService.maybeFireHourlyReport(
        sessionRepository.find(chatId).orElse(session),
        config.dailyApplyLimit()
      );
    }

    if (stateService.countAppliedToday(chatId) >= config.dailyApplyLimit()) {
      finalReportService.sendFinalAndClearSession(session, config.dailyApplyLimit());
      return CycleOutcome.DAILY_LIMIT_REACHED;
    }

    log.info("polling_cycle.completed chatId={}", chatId);
    meterRegistry.counter("hh.polling_cycle.completed").increment();
    return CycleOutcome.COMPLETED;
  }

  private void processOneVacancy(
    VacancyCandidate candidate, UserSearchConfig config, String hhtoken, SearchSession session
  ) {
    if (candidate.hasTest()) {
      claimService.recordSkipped(candidate, config, VacancyStatus.REQUIRES_TEST, clock.instant());
      return;
    }

    if (candidate.matchesExclude(config.excludeKeywords())) {
      claimService.recordSkipped(candidate, config, VacancyStatus.SKIPPED, clock.instant());
      return;
    }

    VacancyDecision decision = claimService.tryClaim(candidate, config, clock.instant());
    if (!(decision instanceof VacancyDecision.Claimed claimed)) return;

    VacancyStatus result = applyService.applyAndPersist(
      candidate, config, claimed.attemptCount(), hhtoken
    );

    log.info("vacancy.processed chatId={} vacancyId={} status={}",
      config.chatId(), candidate.vacancyId(), result);
  }

  private Map<String, List<VacancyCandidate>> fetchVacancies(UserSearchConfig config) {
    Map<String, List<VacancyCandidate>> result = new LinkedHashMap<>();
    for (String keyword : config.keywords()) {
      try {
        List<VacancyCandidate> fetched = hhApiClient.searchAll(
          keyword,
          config.searchAreas(),
          config.schedules(),
          config.employmentTypes(),
          config.searchField().orElse(null),
          config.publishedWithinDays(),
          config.maxVacanciesPerRun()
        );
        result.put(keyword, fetched);
        log.info("keyword.fetched keyword='{}' count={}", keyword, fetched.size());
      } catch (Exception ex) {
        log.error("keyword.fetch_failed keyword='{}'", keyword, ex);
        result.put(keyword, List.of());
      }
    }
    return result;
  }

  private Optional<UserSearchConfig> buildUserSearchConfig(long chatId) {
    return userSettingsRepository.findByChatId(chatId).map(row ->
      new UserSearchConfig(
        chatId,
        row.resumeId(),
        row.resumeTitle(),
        row.keywords(),
        row.coverLetterTemplate(),
        row.hhtoken(),
        hhConfig.search().excludeKeywords().orElse(List.of()),
        hhConfig.search().area(),
        hhConfig.search().schedule(),
        hhConfig.search().employment(),
        hhConfig.search().searchField(),
        hhConfig.search().dailyApplyLimit(),
        hhConfig.search().publishedWithinHours(),
        hhConfig.search().maxVacanciesPerRun(),
        storageConfig.retentionDays(),
        hhConfig.search().vacancyLeaseMinutes(),
        hhConfig.search().pollIntervalSeconds(),
        hhConfig.search().pollIntervalMaxSeconds(),
        hhConfig.search().sameResultBackoffEnabled(),
        hhConfig.search().applyTotalTimeoutSeconds(),
        hhConfig.search().applyPerAttemptTimeoutSeconds()
      )
    );
  }

  private String getHhtoken(UserSearchConfig config) {
    // hhtoken уже загружен в buildUserSearchConfig — нет смысла делать второй запрос
    return config.hhtoken();
  }
}
