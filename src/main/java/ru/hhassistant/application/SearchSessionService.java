package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
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
import java.util.*;

/**
 * Ядро поисковой сессии.
 *
 * <p>Выполняет один polling-цикл для конкретного пользователя:
 * <ol>
 *   <li>Загружает актуальные настройки из БД.</li>
 *   <li>Валидирует сессию hh.ru.</li>
 *   <li>Получает список вакансий по всем keyword-запросам.</li>
 *   <li>Фильтрует, клеймирует, откликается, сохраняет результаты.</li>
 *   <li>Проверяет дневной лимит.</li>
 *   <li>Проверяет hourly-отчёт.</li>
 * </ol>
 *
 * <p>Сервис инвариантен к Telegram: не знает ничего про чаты, клавиатуры, сообщения.
 * Всё взаимодействие с пользователем через {@link ru.hhassistant.domain.port.NotificationPort}.
 */
@ApplicationScoped
public class SearchSessionService {

    private static final Logger log = Logger.getLogger(SearchSessionService.class);

    @Inject HhPublicApiClient hhApiClient;
    @Inject HhSessionValidator sessionValidator;
    @Inject VacancyFilterService filterService;
    @Inject VacancyClaimService claimService;
    @Inject VacancyApplyService applyService;
    @Inject VacancyStateService stateService;
    @Inject HourlyReportService hourlyReportService;
    @Inject FinalReportService finalReportService;
    @Inject UserSettingsRepository userSettingsRepository;
    @Inject SearchSessionRepository sessionRepository;
    @Inject VacancyRepository vacancyRepository;
    @Inject HhConfig hhConfig;
    @Inject StorageConfig storageConfig;
    @Inject MeterRegistry meterRegistry;
    @Inject Clock clock;
    @Inject NotificationPort notificationPort;

    /**
     * Outcome одного polling-цикла. Используется шедулером для принятия решения
     * о следующем цикле.
     */
    public enum CycleOutcome {
        /** Цикл завершён, ждём следующего расписания. */
        COMPLETED,
        /** Достигнут дневной лимит, сессия завершена. */
        DAILY_LIMIT_REACHED,
        /** Сессия hh.ru невалидна, требуется повторная авторизация. */
        SESSION_INVALID,
        /** hh.ru временно недоступен, повторить позже. */
        HH_TEMP_UNAVAILABLE,
        /** Настройки пользователя неполные или не найдены. */
        CONFIG_MISSING
    }

    /**
     * Выполняет один polling-цикл для сессии.
     * Этот метод работает на virtual thread (блокирующий I/O).
     */
    public CycleOutcome executeCycle(SearchSession session) {
        long chatId = session.chatId();
        Instant now = clock.instant();

        // 1. Загрузить актуальные настройки
        Optional<UserSearchConfig> configOpt = buildUserSearchConfig(chatId);
        if (configOpt.isEmpty()) {
            log.warnf("polling_cycle.config_missing chatId=%d", chatId);
            return CycleOutcome.CONFIG_MISSING;
        }
        UserSearchConfig config = configOpt.get();
        if (!config.isComplete()) {
            log.warnf("polling_cycle.config_incomplete chatId=%d", chatId);
            return CycleOutcome.CONFIG_MISSING;
        }

        // 2. Валидировать сессию hh.ru
        String hhtoken = getHhtoken(config);
        SessionValidationResult validation = sessionValidator.validate(hhtoken);
        switch (validation) {
            case INVALID -> {
                log.errorf("polling_cycle.session_invalid chatId=%d", chatId);
                notificationPort.sendSessionInvalidWarning(chatId);
                return CycleOutcome.SESSION_INVALID;
            }
            case TEMP_UNAVAILABLE -> {
                log.warnf("polling_cycle.hh_temp_unavailable chatId=%d", chatId);
                return CycleOutcome.HH_TEMP_UNAVAILABLE;
            }
            case VALID -> {}
        }

        // 3. Получить и обработать вакансии
        log.infof("polling_cycle.start chatId=%d keywords=%d", chatId, config.keywords().size());
        meterRegistry.counter("hh.polling_cycle.started").increment();

        Map<String, List<VacancyCandidate>> byKeyword = fetchVacancies(config);
        List<VacancyCandidate> candidates = filterService.mergeAndFilter(byKeyword, config);

        log.infof("polling_cycle.candidates chatId=%d count=%d", chatId, candidates.size());
        meterRegistry.gauge("hh.polling_cycle.candidates", candidates.size());

        // 4. Обработать вакансии
        for (VacancyCandidate candidate : candidates) {
            // Проверить лимит перед каждой вакансией
            if (stateService.countAppliedToday(chatId) >= config.dailyApplyLimit()) {
                log.infof("polling_cycle.daily_limit_reached chatId=%d limit=%d",
                    chatId, config.dailyApplyLimit());
                finalReportService.sendFinalAndClearSession(session, config.dailyApplyLimit());
                return CycleOutcome.DAILY_LIMIT_REACHED;
            }

            processOneVacancy(candidate, config, hhtoken, session);

            // Почасовой отчёт проверяем после каждой вакансии
            hourlyReportService.maybeFireHourlyReport(
                sessionRepository.find(chatId).orElse(session),
                config.dailyApplyLimit()
            );
        }

        // Финальная проверка лимита после обработки всего списка
        if (stateService.countAppliedToday(chatId) >= config.dailyApplyLimit()) {
            finalReportService.sendFinalAndClearSession(session, config.dailyApplyLimit());
            return CycleOutcome.DAILY_LIMIT_REACHED;
        }

        log.infof("polling_cycle.completed chatId=%d", chatId);
        meterRegistry.counter("hh.polling_cycle.completed").increment();
        return CycleOutcome.COMPLETED;
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private void processOneVacancy(
        VacancyCandidate candidate, UserSearchConfig config, String hhtoken, SearchSession session
    ) {
        // Вакансии с тестом сразу записываем без попытки отклика
        if (candidate.hasTest()) {
            claimService.recordSkipped(candidate, config, VacancyStatus.REQUIRES_TEST, clock.instant());
            return;
        }

        // Вакансии из exclude-keywords: записываем как SKIPPED
        if (candidate.matchesExclude(config.excludeKeywords())) {
            claimService.recordSkipped(candidate, config, VacancyStatus.SKIPPED, clock.instant());
            return;
        }

        // Пытаемся клеймировать
        VacancyDecision decision = claimService.tryClaim(candidate, config, clock.instant());
        if (!(decision instanceof VacancyDecision.Claimed claimed)) return;

        // Применяем
        VacancyStatus result = applyService.applyAndPersist(
            candidate, config, claimed.attemptCount(), hhtoken
        );

        log.infof("vacancy.processed chatId=%d vacancyId=%s status=%s",
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
                log.infof("keyword.fetched keyword='%s' count=%d", keyword, fetched.size());
            } catch (Exception ex) {
                log.errorf(ex, "keyword.fetch_failed keyword='%s'", keyword);
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
