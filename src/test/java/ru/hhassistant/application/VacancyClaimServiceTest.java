package ru.hhassistant.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.domain.model.*;
import ru.hhassistant.domain.port.VacancyRepository;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VacancyClaimServiceTest {

    private static final long CHAT_ID = 1L;
    private static final String VACANCY_ID = "v456";
    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    @Mock VacancyRepository vacancyRepository;

    private VacancyClaimService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new VacancyClaimService();
        inject(service, "vacancyRepository", vacancyRepository);
        inject(service, "meterRegistry", new SimpleMeterRegistry());
    }

    // ─── tryClaim — VacancyDecision variants ──────────────────────────────────

    @Test
    void tryClaim_claimed_returnsClaimed() {
        var claimed = new VacancyDecision.Claimed(1);
        stubTryClaim(claimed);

        var result = service.tryClaim(candidate(), config(), NOW);

        assertThat(result).isInstanceOf(VacancyDecision.Claimed.class);
        assertThat(((VacancyDecision.Claimed) result).attemptCount()).isEqualTo(1);
    }

    @Test
    void tryClaim_skipTerminal_delegatesAndReturns() {
        var terminal = new VacancyDecision.SkipTerminal(VacancyStatus.APPLIED, 2);
        stubTryClaim(terminal);

        var result = service.tryClaim(candidate(), config(), NOW);

        assertThat(result).isInstanceOf(VacancyDecision.SkipTerminal.class);
        assertThat(((VacancyDecision.SkipTerminal) result).currentStatus()).isEqualTo(VacancyStatus.APPLIED);
    }

    @Test
    void tryClaim_skipBackoff_delegatesAndReturns() {
        var backoff = new VacancyDecision.SkipBackoff(NOW.plusSeconds(300), 1, VacancyStatus.APPLY_TEMP_ERROR);
        stubTryClaim(backoff);

        var result = service.tryClaim(candidate(), config(), NOW);

        assertThat(result).isInstanceOf(VacancyDecision.SkipBackoff.class);
    }

    @Test
    void tryClaim_skipInProgress_delegatesAndReturns() {
        var inProgress = new VacancyDecision.SkipInProgress(NOW.plusSeconds(600), 0);
        stubTryClaim(inProgress);

        var result = service.tryClaim(candidate(), config(), NOW);

        assertThat(result).isInstanceOf(VacancyDecision.SkipInProgress.class);
    }

    @Test
    void tryClaim_passesCorrectParametersToRepository() {
        stubTryClaim(new VacancyDecision.Claimed(0));
        var c = candidate();
        service.tryClaim(c, config(), NOW);

        verify(vacancyRepository).tryClaim(
            anyLong(),
            eq(VACANCY_ID),
            eq("Java разработчик"),
            eq("ACME"),
            eq("https://hh.ru/vacancy/" + VACANCY_ID),
            isNull(),
            eq(10),    // leaseMinutes from config
            eq(30),    // retentionDays from config
            eq(NOW)
        );
    }

    // ─── recordSkipped ────────────────────────────────────────────────────────

    @Test
    void recordSkipped_callsRepositoryWithCorrectArgs() {
        service.recordSkipped(candidate(), config(), VacancyStatus.SKIPPED, NOW);

        verify(vacancyRepository).upsertSkipped(
            anyLong(), eq(VACANCY_ID),
            eq("Java разработчик"), eq("ACME"),
            eq("https://hh.ru/vacancy/" + VACANCY_ID),
            isNull(),
            eq(VacancyStatus.SKIPPED),
            eq(NOW)
        );
    }

    @Test
    void recordSkipped_requiresTest_callsRepositoryWithCorrectStatus() {
        service.recordSkipped(candidate(), config(), VacancyStatus.REQUIRES_TEST, NOW);

        verify(vacancyRepository).upsertSkipped(
            anyLong(), anyString(), anyString(), anyString(),
            anyString(), any(), eq(VacancyStatus.REQUIRES_TEST), any()
        );
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static VacancyCandidate candidate() {
        return new VacancyCandidate(VACANCY_ID, "Java разработчик", "ACME",
            "https://hh.ru/vacancy/" + VACANCY_ID, null, false, "Москва");
    }

    private static UserSearchConfig config() {
        return new UserSearchConfig(
            CHAT_ID, "resume-abc", "My Resume", List.of("java"),
            null, "hhtoken", List.of(), List.of(1), List.of(), List.of(),
            Optional.empty(), 50, 24, 100, 30, 10,
            60.0, 300.0, false, 30, 15
        );
    }

    private void stubTryClaim(VacancyDecision decision) {
        when(vacancyRepository.tryClaim(
            anyLong(), anyString(), anyString(), anyString(),
            anyString(), any(), anyInt(), anyInt(), any()
        )).thenReturn(decision);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
