package ru.hhassistant.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VacancyDecisionTest {

    @Test
    void claimed_storesAttemptCount() {
        var d = new VacancyDecision.Claimed(2);
        assertThat(d.attemptCount()).isEqualTo(2);
    }

    @Test
    void skipTerminal_storesStatusAndAttemptCount() {
        var d = new VacancyDecision.SkipTerminal(VacancyStatus.APPLIED, 3);
        assertThat(d.currentStatus()).isEqualTo(VacancyStatus.APPLIED);
        assertThat(d.attemptCount()).isEqualTo(3);
    }

    @Test
    void skipBackoff_storesNextRetryAtAndAttemptCount() {
        Instant future = Instant.now().plusSeconds(300);
        var d = new VacancyDecision.SkipBackoff(future, 1, VacancyStatus.APPLY_TEMP_ERROR);
        assertThat(d.nextRetryAt()).isEqualTo(future);
        assertThat(d.attemptCount()).isEqualTo(1);
        assertThat(d.currentStatus()).isEqualTo(VacancyStatus.APPLY_TEMP_ERROR);
    }

    @Test
    void skipInProgress_storesLeaseExpiresAt() {
        Instant expiry = Instant.now().plusSeconds(600);
        var d = new VacancyDecision.SkipInProgress(expiry, 0);
        assertThat(d.leaseExpiresAt()).isEqualTo(expiry);
        assertThat(d.attemptCount()).isEqualTo(0);
    }

    @Test
    void sealedInterface_allVariantsPatternMatch() {
        // Проверяем исчерпанность switch для sealed interface
        VacancyDecision[] decisions = {
            new VacancyDecision.Claimed(1),
            new VacancyDecision.SkipTerminal(VacancyStatus.SKIPPED, 0),
            new VacancyDecision.SkipBackoff(Instant.now(), 2, VacancyStatus.APPLY_TEMP_ERROR),
            new VacancyDecision.SkipInProgress(Instant.now(), 0)
        };
        for (var d : decisions) {
            String label = switch (d) {
                case VacancyDecision.Claimed c -> "claimed";
                case VacancyDecision.SkipTerminal t -> "terminal";
                case VacancyDecision.SkipBackoff b -> "backoff";
                case VacancyDecision.SkipInProgress p -> "inprogress";
            };
            assertThat(label).isNotBlank();
        }
    }
}
