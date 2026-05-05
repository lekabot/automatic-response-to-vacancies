package ru.hhassistant.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.config.StorageConfig;
import ru.hhassistant.domain.model.*;
import ru.hhassistant.domain.port.*;
import ru.hhassistant.infrastructure.hh.HhPublicApiClient;
import ru.hhassistant.infrastructure.hh.HhSessionValidator;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");
    private static final Instant SESSION_START = Instant.parse("2026-04-01T10:00:00Z");
    private static final long CHAT_ID = 1L;

    @Mock HhPublicApiClient hhApiClient;
    @Mock HhSessionValidator sessionValidator;
    @Mock VacancyFilterService filterService;
    @Mock VacancyClaimService claimService;
    @Mock VacancyApplyService applyService;
    @Mock VacancyStateService stateService;
    @Mock HourlyReportService hourlyReportService;
    @Mock FinalReportService finalReportService;
    @Mock UserSettingsRepository userSettingsRepository;
    @Mock SearchSessionRepository sessionRepository;
    @Mock VacancyRepository vacancyRepository;
    @Mock HhConfig hhConfig;
    @Mock HhConfig.SearchConfig searchConfig;
    @Mock StorageConfig storageConfig;
    @Mock NotificationPort notificationPort;

    private SearchSessionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SearchSessionService();
        inject(service, "hhApiClient", hhApiClient);
        inject(service, "sessionValidator", sessionValidator);
        inject(service, "filterService", filterService);
        inject(service, "claimService", claimService);
        inject(service, "applyService", applyService);
        inject(service, "stateService", stateService);
        inject(service, "hourlyReportService", hourlyReportService);
        inject(service, "finalReportService", finalReportService);
        inject(service, "userSettingsRepository", userSettingsRepository);
        inject(service, "sessionRepository", sessionRepository);
        inject(service, "vacancyRepository", vacancyRepository);
        inject(service, "hhConfig", hhConfig);
        inject(service, "storageConfig", storageConfig);
        inject(service, "meterRegistry", new SimpleMeterRegistry());
        inject(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        inject(service, "notificationPort", notificationPort);

        // Default stubs for hhConfig
        lenient().when(hhConfig.search()).thenReturn(searchConfig);
        lenient().when(searchConfig.excludeKeywords()).thenReturn(Optional.empty());
        lenient().when(searchConfig.area()).thenReturn(List.of(113));
        lenient().when(searchConfig.schedule()).thenReturn(List.of("remote", "fullDay"));
        lenient().when(searchConfig.employment()).thenReturn(List.of("full"));
        lenient().when(searchConfig.searchField()).thenReturn(Optional.empty());
        lenient().when(searchConfig.publishedWithinHours()).thenReturn(24);
        lenient().when(searchConfig.maxVacanciesPerRun()).thenReturn(100);
        lenient().when(searchConfig.dailyApplyLimit()).thenReturn(50);
        lenient().when(searchConfig.pollIntervalSeconds()).thenReturn(60.0);
        lenient().when(searchConfig.pollIntervalMaxSeconds()).thenReturn(300.0);
        lenient().when(searchConfig.sameResultBackoffEnabled()).thenReturn(false);
        lenient().when(searchConfig.vacancyLeaseMinutes()).thenReturn(10);
        lenient().when(searchConfig.applyTotalTimeoutSeconds()).thenReturn(120);
        lenient().when(searchConfig.applyPerAttemptTimeoutSeconds()).thenReturn(35);
        lenient().when(storageConfig.retentionDays()).thenReturn(30);
    }

    // ─── CONFIG_MISSING paths ─────────────────────────────────────────────────

    @Test
    void executeCycle_noUserSettings_returnsConfigMissing() {
        when(userSettingsRepository.findByChatId(anyLong())).thenReturn(Optional.empty());

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.CONFIG_MISSING);
        verifyNoInteractions(sessionValidator, hhApiClient);
    }

    @Test
    void executeCycle_incompleteConfig_noResumeId_returnsConfigMissing() {
        stubUserSettings(null, List.of("java"), "hhtoken");

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.CONFIG_MISSING);
        verifyNoInteractions(sessionValidator);
    }

    @Test
    void executeCycle_incompleteConfig_noKeywords_returnsConfigMissing() {
        stubUserSettings("resume-abc", List.of(), "hhtoken");

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.CONFIG_MISSING);
        verifyNoInteractions(sessionValidator);
    }

    // ─── SESSION validation paths ─────────────────────────────────────────────

    @Test
    void executeCycle_sessionInvalid_returnsSessionInvalid_notifiesUser() {
        stubUserSettings("resume-abc", List.of("java"), "bad-token");
        when(sessionValidator.validate("bad-token")).thenReturn(SessionValidationResult.INVALID);

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.SESSION_INVALID);
        verify(notificationPort).sendSessionInvalidWarning(CHAT_ID);
        verifyNoInteractions(hhApiClient);
    }

    @Test
    void executeCycle_hhTempUnavailable_returnsTempUnavailable_notifiesUser() {
        stubUserSettings("resume-abc", List.of("java"), "good-token");
        when(sessionValidator.validate("good-token")).thenReturn(SessionValidationResult.TEMP_UNAVAILABLE);

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.HH_TEMP_UNAVAILABLE);
        verify(notificationPort).sendHhTempUnavailableWarning(CHAT_ID);
    }

    // ─── Happy paths ──────────────────────────────────────────────────────────

    @Test
    void executeCycle_validSession_noCandidates_returnsCompleted() throws Exception {
        stubUserSettings("resume-abc", List.of("java"), "valid-token");
        when(sessionValidator.validate("valid-token")).thenReturn(SessionValidationResult.VALID);
        when(hhApiClient.searchAll(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());
        when(filterService.mergeAndFilter(any(), any())).thenReturn(List.of());
        when(stateService.countAppliedToday(CHAT_ID)).thenReturn(0);

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.COMPLETED);
        verifyNoInteractions(claimService, applyService);
    }

    @Test
    void executeCycle_dailyLimitAlreadyReached_returnsLimitReached() throws Exception {
        stubUserSettings("resume-abc", List.of("java"), "valid-token");
        when(sessionValidator.validate("valid-token")).thenReturn(SessionValidationResult.VALID);
        when(hhApiClient.searchAll(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());
        when(filterService.mergeAndFilter(any(), any())).thenReturn(List.of(candidate("v1")));
        // Already at limit before processing any vacancy
        when(stateService.countAppliedToday(CHAT_ID)).thenReturn(50);

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.DAILY_LIMIT_REACHED);
        verify(finalReportService).sendFinalAndClearSession(any(), eq(50));
    }

    // ─── processOneVacancy paths ──────────────────────────────────────────────

    @Test
    void executeCycle_candidateWithTest_skipsWithRequiresTest() throws Exception {
        stubUserSettings("resume-abc", List.of("java"), "valid-token");
        when(sessionValidator.validate("valid-token")).thenReturn(SessionValidationResult.VALID);
        VacancyCandidate testCandidate = new VacancyCandidate(
            "v1", "Java разработчик", "ACME", "url", null, true, "Москва"
        );
        when(hhApiClient.searchAll(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());
        when(filterService.mergeAndFilter(any(), any())).thenReturn(List.of(testCandidate));
        when(stateService.countAppliedToday(CHAT_ID)).thenReturn(0);
        when(sessionRepository.find(anyLong())).thenReturn(Optional.of(session()));

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.COMPLETED);
        verify(claimService).recordSkipped(eq(testCandidate), any(), eq(VacancyStatus.REQUIRES_TEST), any());
        verifyNoInteractions(applyService);
    }

    @Test
    void executeCycle_candidateMatchesExclude_skipsWithSkipped() throws Exception {
        stubUserSettings("resume-abc", List.of("java"), "valid-token");
        when(sessionValidator.validate("valid-token")).thenReturn(SessionValidationResult.VALID);
        when(searchConfig.excludeKeywords()).thenReturn(Optional.of(List.of("junior corp")));
        VacancyCandidate excludedCandidate = new VacancyCandidate(
            "v1", "Java Developer", "Junior Corp", "url", null, false, "Москва"
        );
        when(hhApiClient.searchAll(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());
        when(filterService.mergeAndFilter(any(), any())).thenReturn(List.of(excludedCandidate));
        when(stateService.countAppliedToday(CHAT_ID)).thenReturn(0);
        when(sessionRepository.find(anyLong())).thenReturn(Optional.of(session()));

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.COMPLETED);
        verify(claimService).recordSkipped(eq(excludedCandidate), any(), eq(VacancyStatus.SKIPPED), any());
        verifyNoInteractions(applyService);
    }

    @Test
    void executeCycle_candidateClaimed_callsApplyService() throws Exception {
        stubUserSettings("resume-abc", List.of("java"), "valid-token");
        when(sessionValidator.validate("valid-token")).thenReturn(SessionValidationResult.VALID);
        VacancyCandidate candidate = candidate("v1");
        when(hhApiClient.searchAll(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());
        when(filterService.mergeAndFilter(any(), any())).thenReturn(List.of(candidate));
        when(stateService.countAppliedToday(CHAT_ID)).thenReturn(0);
        when(claimService.tryClaim(any(), any(), any())).thenReturn(new VacancyDecision.Claimed(1));
        when(applyService.applyAndPersist(any(), any(), anyInt(), any()))
            .thenReturn(VacancyStatus.APPLIED);
        when(sessionRepository.find(anyLong())).thenReturn(Optional.of(session()));

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.COMPLETED);
        verify(applyService).applyAndPersist(eq(candidate), any(), eq(1), eq("valid-token"));
    }

    @Test
    void executeCycle_candidateSkipInProgress_doesNotCallApplyService() throws Exception {
        stubUserSettings("resume-abc", List.of("java"), "valid-token");
        when(sessionValidator.validate("valid-token")).thenReturn(SessionValidationResult.VALID);
        when(hhApiClient.searchAll(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of());
        when(filterService.mergeAndFilter(any(), any())).thenReturn(List.of(candidate("v1")));
        when(stateService.countAppliedToday(CHAT_ID)).thenReturn(0);
        when(claimService.tryClaim(any(), any(), any()))
            .thenReturn(new VacancyDecision.SkipInProgress(NOW.plusSeconds(600), 1));
        when(sessionRepository.find(anyLong())).thenReturn(Optional.of(session()));

        var outcome = service.executeCycle(session());

        assertThat(outcome).isEqualTo(SearchSessionService.CycleOutcome.COMPLETED);
        verifyNoInteractions(applyService);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void stubUserSettings(String resumeId, List<String> keywords, String hhtoken) {
        var row = new UserSettingsRepository.UserSettingsRow(
            CHAT_ID, "user@test.ru", hhtoken, keywords, null, resumeId, "My Resume"
        );
        when(userSettingsRepository.findByChatId(anyLong())).thenReturn(Optional.of(row));
    }

    private static SearchSession session() {
        return new SearchSession(CHAT_ID, SESSION_START, null);
    }

    private static VacancyCandidate candidate(String id) {
        return new VacancyCandidate(id, "Java разработчик", "ACME",
            "https://hh.ru/vacancy/" + id, null, false, "Москва");
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
