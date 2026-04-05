package ru.hhassistant.infrastructure.db;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.hhassistant.domain.model.VacancyDecision;
import ru.hhassistant.domain.model.VacancyStatus;
import ru.hhassistant.domain.port.VacancyRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты репозитория против реального PostgreSQL.
 * Quarkus DevServices автоматически поднимает PostgreSQL через Testcontainers.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VacancyRepositoryIntegrationTest {

    @Inject VacancyRepository vacancyRepository;

    private static final long CHAT_ID = 999_001L;
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void cleanUp() {
        vacancyRepository.deleteAll(CHAT_ID);
    }

    @Test
    void tryClaim_newVacancy_returnsClaimed() {
        VacancyDecision decision = claim("v001");
        assertThat(decision).isInstanceOf(VacancyDecision.Claimed.class);
        assertThat(((VacancyDecision.Claimed) decision).attemptCount()).isEqualTo(1);
    }

    @Test
    void tryClaim_terminalVacancy_returnsSkipTerminal() {
        claim("v002");
        vacancyRepository.persistOutcome(CHAT_ID, "v002", VacancyStatus.APPLIED, null, null, NOW);

        VacancyDecision decision = claim("v002");
        assertThat(decision).isInstanceOf(VacancyDecision.SkipTerminal.class);
        assertThat(((VacancyDecision.SkipTerminal) decision).currentStatus())
            .isEqualTo(VacancyStatus.APPLIED);
    }

    @Test
    void tryClaim_inProgressWithActiveLease_returnsSkipInProgress() {
        // Клеймируем но НЕ финализируем — останется IN_PROGRESS
        claim("v003");

        VacancyDecision decision = claim("v003");
        assertThat(decision).isInstanceOf(VacancyDecision.SkipInProgress.class);
    }

    @Test
    void tryClaim_backoffVacancy_returnsSkipBackoff() {
        claim("v004");
        Instant futureRetry = NOW.plusSeconds(3600);
        vacancyRepository.persistOutcome(CHAT_ID, "v004", VacancyStatus.APPLY_TEMP_ERROR,
            "timeout", futureRetry, NOW);

        VacancyDecision decision = claim("v004");
        assertThat(decision).isInstanceOf(VacancyDecision.SkipBackoff.class);
    }

    @Test
    void tryClaim_backoffVacancy_afterRetryExpires_returnsClaimed() {
        claim("v005");
        Instant pastRetry = NOW.minusSeconds(1);
        vacancyRepository.persistOutcome(CHAT_ID, "v005", VacancyStatus.APPLY_TEMP_ERROR,
            "timeout", pastRetry, NOW);

        VacancyDecision decision = claim("v005");
        assertThat(decision).isInstanceOf(VacancyDecision.Claimed.class);
        assertThat(((VacancyDecision.Claimed) decision).attemptCount()).isEqualTo(2);
    }

    @Test
    void countAppliedToday_countsOnlyApplied() {
        claim("v010"); vacancyRepository.persistOutcome(CHAT_ID, "v010", VacancyStatus.APPLIED, null, null, NOW);
        claim("v011"); vacancyRepository.persistOutcome(CHAT_ID, "v011", VacancyStatus.SKIPPED, null, null, NOW);
        claim("v012"); vacancyRepository.persistOutcome(CHAT_ID, "v012", VacancyStatus.APPLIED, null, null, NOW);

        int count = vacancyRepository.countAppliedToday(CHAT_ID, NOW.minusSeconds(3600));
        assertThat(count).isEqualTo(2);
    }

    @Test
    void batchPeek_returnsCorrectPaths() {
        claim("v020");
        vacancyRepository.persistOutcome(CHAT_ID, "v020", VacancyStatus.APPLIED, null, null, NOW);

        Map<String, VacancyRepository.ClaimPath> paths = vacancyRepository.batchPeek(
            CHAT_ID, List.of("v020", "v999"), 10, 30, NOW);

        assertThat(paths.get("v020")).isEqualTo(VacancyRepository.ClaimPath.TERMINAL);
        assertThat(paths.get("v999")).isEqualTo(VacancyRepository.ClaimPath.CLAIMABLE);
    }

    @Test
    void sessionStats_correctCountsPerStatus() {
        claim("v030"); vacancyRepository.persistOutcome(CHAT_ID, "v030", VacancyStatus.APPLIED, null, null, NOW);
        claim("v031"); vacancyRepository.persistOutcome(CHAT_ID, "v031", VacancyStatus.APPLIED, null, null, NOW);
        vacancyRepository.upsertSkipped(CHAT_ID, "v032", "T", "E", "U", null, VacancyStatus.SKIPPED, NOW);

        var stats = vacancyRepository.sessionStats(CHAT_ID, NOW.minusSeconds(3600), NOW, 200);
        assertThat(stats.applied()).isEqualTo(2);
        assertThat(stats.skipped()).isEqualTo(1);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private VacancyDecision claim(String vacancyId) {
        return vacancyRepository.tryClaim(
            CHAT_ID, vacancyId, "Title " + vacancyId, "Employer", "https://hh.ru/" + vacancyId,
            null, 10, 30, NOW
        );
    }
}
