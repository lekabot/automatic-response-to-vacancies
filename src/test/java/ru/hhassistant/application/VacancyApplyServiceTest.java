package ru.hhassistant.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.domain.model.*;
import ru.hhassistant.domain.policy.RetryPolicy;
import ru.hhassistant.domain.port.VacancyRepository;
import ru.hhassistant.infrastructure.hh.ApplyOutcome;
import ru.hhassistant.infrastructure.hh.HhApplyClient;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VacancyApplyServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");
    private static final long CHAT_ID = 1L;
    private static final String VACANCY_ID = "v123";
    private static final String RESUME_ID = "resume-abc";
    private static final String HHTOKEN = "hhtoken-xyz";

    @Mock HhApplyClient applyClient;
    @Mock VacancyRepository vacancyRepository;
    @Mock RetryPolicy retryPolicy;

    private VacancyApplyService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new VacancyApplyService();
        inject(service, "applyClient", applyClient);
        inject(service, "vacancyRepository", vacancyRepository);
        inject(service, "retryPolicy", retryPolicy);
        inject(service, "meterRegistry", new SimpleMeterRegistry());
        inject(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ─── APPLIED ──────────────────────────────────────────────────────────────

    @Test
    void applyAndPersist_applied_returnsApplied() {
        when(applyClient.apply(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.applied());

        VacancyStatus result = service.applyAndPersist(candidate(), config(null), 1, HHTOKEN);

        assertThat(result).isEqualTo(VacancyStatus.APPLIED);
        verify(vacancyRepository).persistOutcome(
            eq(CHAT_ID), eq(VACANCY_ID), eq(VacancyStatus.APPLIED),
            isNull(), isNull(), eq(NOW)
        );
        verify(retryPolicy, never()).computeNextRetryAt(anyInt());
    }

    // ─── ALREADY_APPLIED ──────────────────────────────────────────────────────

    @Test
    void applyAndPersist_alreadyApplied_returnsAlreadyApplied() {
        when(applyClient.apply(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.alreadyApplied());

        VacancyStatus result = service.applyAndPersist(candidate(), config(null), 1, HHTOKEN);

        assertThat(result).isEqualTo(VacancyStatus.ALREADY_APPLIED);
        verify(vacancyRepository).persistOutcome(
            eq(CHAT_ID), eq(VACANCY_ID), eq(VacancyStatus.ALREADY_APPLIED),
            any(), isNull(), any()
        );
    }

    // ─── PERM_ERROR ───────────────────────────────────────────────────────────

    @Test
    void applyAndPersist_permError_returnsPermError_noRetryScheduled() {
        when(applyClient.apply(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.permError("validationError"));

        VacancyStatus result = service.applyAndPersist(candidate(), config(null), 1, HHTOKEN);

        assertThat(result).isEqualTo(VacancyStatus.APPLY_PERM_ERROR);
        verify(retryPolicy, never()).computeNextRetryAt(anyInt());
        verify(vacancyRepository).persistOutcome(
            eq(CHAT_ID), eq(VACANCY_ID), eq(VacancyStatus.APPLY_PERM_ERROR),
            eq("validationError"), isNull(), any()
        );
    }

    @Test
    void applyAndPersist_authError_mapsToPermError() {
        when(applyClient.apply(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.authError("no_xsrf"));

        VacancyStatus result = service.applyAndPersist(candidate(), config(null), 1, HHTOKEN);

        assertThat(result).isEqualTo(VacancyStatus.APPLY_PERM_ERROR);
    }

    // ─── TEMP_ERROR ───────────────────────────────────────────────────────────

    @Test
    void applyAndPersist_tempError_schedulesRetry() {
        Instant nextRetry = NOW.plusSeconds(120);
        when(applyClient.apply(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.tempError("transport_error", "Connection refused"));
        when(retryPolicy.computeNextRetryAt(2)).thenReturn(nextRetry);

        VacancyStatus result = service.applyAndPersist(candidate(), config(null), 2, HHTOKEN);

        assertThat(result).isEqualTo(VacancyStatus.APPLY_TEMP_ERROR);
        verify(retryPolicy).computeNextRetryAt(2);
        verify(vacancyRepository).persistOutcome(
            eq(CHAT_ID), eq(VACANCY_ID), eq(VacancyStatus.APPLY_TEMP_ERROR),
            contains("transport_error"), eq(nextRetry), any()
        );
    }

    // ─── TIMEOUT ──────────────────────────────────────────────────────────────

    @Test
    void applyAndPersist_timeout_isRetryable_schedulesRetry() {
        Instant nextRetry = NOW.plusSeconds(60);
        when(applyClient.apply(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.timeout());
        when(retryPolicy.computeNextRetryAt(1)).thenReturn(nextRetry);

        VacancyStatus result = service.applyAndPersist(candidate(), config(null), 1, HHTOKEN);

        assertThat(result).isEqualTo(VacancyStatus.APPLY_TIMEOUT);
        verify(retryPolicy).computeNextRetryAt(1);
    }

    // ─── Unexpected exception ─────────────────────────────────────────────────

    @Test
    void applyAndPersist_unexpectedException_returnsTempError() {
        Instant nextRetry = NOW.plusSeconds(60);
        when(applyClient.apply(any(), any(), any(), any(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("Unexpected NullPointer"));
        when(retryPolicy.computeNextRetryAt(anyInt())).thenReturn(nextRetry);

        VacancyStatus result = service.applyAndPersist(candidate(), config(null), 1, HHTOKEN);

        assertThat(result).isEqualTo(VacancyStatus.APPLY_TEMP_ERROR);
    }

    // ─── Cover letter rendering ────────────────────────────────────────────────

    @Test
    void applyAndPersist_coverLetterTemplate_renderedAndPassedToClient() {
        when(applyClient.apply(eq(VACANCY_ID), eq(RESUME_ID), contains("ACME"), eq(HHTOKEN), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.applied());

        service.applyAndPersist(candidate(), config("Привет {employer}!"), 1, HHTOKEN);

        verify(applyClient).apply(eq(VACANCY_ID), eq(RESUME_ID), eq("Привет ACME!"), eq(HHTOKEN), anyInt(), anyInt());
    }

    @Test
    void applyAndPersist_nullCoverLetter_passesEmptyStringToClient() {
        when(applyClient.apply(any(), any(), eq(""), any(), anyInt(), anyInt()))
            .thenReturn(ApplyOutcome.applied());

        service.applyAndPersist(candidate(), config(null), 1, HHTOKEN);

        verify(applyClient).apply(any(), any(), eq(""), any(), anyInt(), anyInt());
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static VacancyCandidate candidate() {
        return new VacancyCandidate(VACANCY_ID, "Java разработчик", "ACME",
            "https://hh.ru/vacancy/" + VACANCY_ID, null, false, "Москва");
    }

    private static UserSearchConfig config(String coverLetterTemplate) {
        return new UserSearchConfig(
            CHAT_ID, RESUME_ID, "My Resume", List.of("java"),
            coverLetterTemplate, HHTOKEN, List.of(), List.of(1), List.of(), List.of(),
            Optional.empty(), 50, 24, 100, 30, 10,
            60.0, 300.0, false, 30, 15
        );
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
