package ru.hhassistant.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VacancyProcessingStateTest {

    private static final Instant BASE = Instant.parse("2026-04-01T12:00:00Z");

    // ─── isLeaseExpired ───────────────────────────────────────────────────────

    @Test
    void isLeaseExpired_nullProcessingStartedAt_returnsTrue() {
        var state = state(null);
        assertThat(state.isLeaseExpired(10, BASE)).isTrue();
    }

    @Test
    void isLeaseExpired_withinLease_returnsFalse() {
        Instant startedAt = BASE.minusSeconds(5 * 60); // 5 minutes ago
        var state = state(startedAt);
        // lease = 10 minutes, started 5 min ago → not expired
        assertThat(state.isLeaseExpired(10, BASE)).isFalse();
    }

    @Test
    void isLeaseExpired_leaseElapsed_returnsTrue() {
        Instant startedAt = BASE.minusSeconds(15 * 60); // 15 minutes ago
        var state = state(startedAt);
        // lease = 10 minutes, started 15 min ago → expired
        assertThat(state.isLeaseExpired(10, BASE)).isTrue();
    }

    @Test
    void isLeaseExpired_exactlyAtBoundary_returnsFalse() {
        Instant startedAt = BASE.minusSeconds(10 * 60); // exactly 10 minutes ago
        var state = state(startedAt);
        // isAfter(startedAt + 10min) → isAfter(BASE) → false
        assertThat(state.isLeaseExpired(10, BASE)).isFalse();
    }

    // ─── isReadyForRetry ──────────────────────────────────────────────────────

    @Test
    void isReadyForRetry_nullNextRetryAt_returnsTrue() {
        var state = stateWithRetry(null);
        assertThat(state.isReadyForRetry(BASE)).isTrue();
    }

    @Test
    void isReadyForRetry_nextRetryInFuture_returnsFalse() {
        Instant future = BASE.plusSeconds(300);
        var state = stateWithRetry(future);
        assertThat(state.isReadyForRetry(BASE)).isFalse();
    }

    @Test
    void isReadyForRetry_nextRetryInPast_returnsTrue() {
        Instant past = BASE.minusSeconds(300);
        var state = stateWithRetry(past);
        assertThat(state.isReadyForRetry(BASE)).isTrue();
    }

    @Test
    void isReadyForRetry_exactlyAtNextRetry_returnsTrue() {
        // isAfter check: !now.isBefore(nextRetryAt) → now >= nextRetryAt
        var state = stateWithRetry(BASE);
        assertThat(state.isReadyForRetry(BASE)).isTrue();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static VacancyProcessingState state(Instant processingStartedAt) {
        return new VacancyProcessingState(
            1L, "v1", "Java Dev", "ACME", "url", null,
            VacancyStatus.IN_PROGRESS, 1, null, BASE, null,
            processingStartedAt, BASE
        );
    }

    private static VacancyProcessingState stateWithRetry(Instant nextRetryAt) {
        return new VacancyProcessingState(
            1L, "v1", "Java Dev", "ACME", "url", null,
            VacancyStatus.APPLY_TEMP_ERROR, 2, "transport_error", BASE, nextRetryAt,
            null, BASE
        );
    }
}
