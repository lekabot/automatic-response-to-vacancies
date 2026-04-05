package ru.hhassistant.domain;

import org.junit.jupiter.api.Test;
import ru.hhassistant.domain.model.VacancyStatus;

import static org.assertj.core.api.Assertions.assertThat;

class VacancyStatusTest {

    @Test
    void terminalStatuses_areCorrect() {
        assertThat(VacancyStatus.APPLIED.isTerminal()).isTrue();
        assertThat(VacancyStatus.ALREADY_APPLIED.isTerminal()).isTrue();
        assertThat(VacancyStatus.SKIPPED.isTerminal()).isTrue();
        assertThat(VacancyStatus.REQUIRES_TEST.isTerminal()).isTrue();
        assertThat(VacancyStatus.APPLY_PERM_ERROR.isTerminal()).isTrue();
    }

    @Test
    void nonTerminalStatuses_areNotTerminal() {
        assertThat(VacancyStatus.IN_PROGRESS.isTerminal()).isFalse();
        assertThat(VacancyStatus.APPLY_TIMEOUT.isTerminal()).isFalse();
        assertThat(VacancyStatus.APPLY_TEMP_ERROR.isTerminal()).isFalse();
    }

    @Test
    void retryableStatuses_areCorrect() {
        assertThat(VacancyStatus.APPLY_TIMEOUT.isRetryable()).isTrue();
        assertThat(VacancyStatus.APPLY_TEMP_ERROR.isRetryable()).isTrue();
    }

    @Test
    void nonRetryableStatuses_areNotRetryable() {
        assertThat(VacancyStatus.APPLIED.isRetryable()).isFalse();
        assertThat(VacancyStatus.APPLY_PERM_ERROR.isRetryable()).isFalse();
        assertThat(VacancyStatus.IN_PROGRESS.isRetryable()).isFalse();
        assertThat(VacancyStatus.SKIPPED.isRetryable()).isFalse();
    }

    @Test
    void allStatusValues_coveredByIsTerminal() {
        // Exhaustiveness check: все значения обработаны без NPE/MatchException
        for (VacancyStatus s : VacancyStatus.values()) {
            assertThat(s.isTerminal() || !s.isTerminal()).isTrue();
        }
    }
}
