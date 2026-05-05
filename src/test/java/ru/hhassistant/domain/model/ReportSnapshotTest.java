package ru.hhassistant.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportSnapshotTest {

    private static final Instant START = Instant.parse("2026-04-01T08:00:00Z");
    private static final Instant END   = Instant.parse("2026-04-01T20:00:00Z");

    @Test
    void totalProcessed_sumsAllCategories() {
        var s = snapshot(5, 2, 3, 1, 1, 1, 1, 50);
        // 5+2+3+1+1+1+1 = 14
        assertThat(s.totalProcessed()).isEqualTo(14);
    }

    @Test
    void totalProcessed_allZero_returnsZero() {
        assertThat(snapshot(0, 0, 0, 0, 0, 0, 0, 50).totalProcessed()).isEqualTo(0);
    }

    @Test
    void retryLater_sumOfTimeoutAndTempError() {
        var s = snapshot(5, 0, 0, 0, 3, 2, 0, 50);
        assertThat(s.retryLater()).isEqualTo(5);
    }

    @Test
    void limitReached_whenAppliedEqualsLimit_true() {
        var s = snapshot(10, 0, 0, 0, 0, 0, 0, 10);
        assertThat(s.limitReached()).isTrue();
    }

    @Test
    void limitReached_whenAppliedExceedsLimit_true() {
        var s = snapshot(15, 0, 0, 0, 0, 0, 0, 10);
        assertThat(s.limitReached()).isTrue();
    }

    @Test
    void limitReached_whenAppliedBelowLimit_false() {
        var s = snapshot(5, 0, 0, 0, 0, 0, 0, 50);
        assertThat(s.limitReached()).isFalse();
    }

    @Test
    void withRequiresTestVacancies_returnsSnapshotWithNewRefs() {
        var original = snapshot(3, 0, 0, 2, 0, 0, 0, 50);
        var refs = List.of(new ReportSnapshot.TestVacancyRef("Java Dev", "ACME", "https://hh.ru/vacancy/1"));

        var updated = original.withRequiresTestVacancies(refs);

        assertThat(updated.requiresTestVacancies()).hasSize(1);
        assertThat(updated.requiresTestVacancies().get(0).title()).isEqualTo("Java Dev");
        assertThat(updated.applied()).isEqualTo(original.applied());
        assertThat(updated.chatId()).isEqualTo(original.chatId());
    }

    @Test
    void withRequiresTestVacancies_emptyList_clearsOldRefs() {
        var original = snapshot(1, 0, 0, 1, 0, 0, 0, 50);
        var updated = original.withRequiresTestVacancies(List.of());
        assertThat(updated.requiresTestVacancies()).isEmpty();
    }

    @Test
    void testVacancyRef_holdsFields() {
        var ref = new ReportSnapshot.TestVacancyRef("Python Dev", "BigCo", "https://hh.ru/vacancy/42");
        assertThat(ref.title()).isEqualTo("Python Dev");
        assertThat(ref.employer()).isEqualTo("BigCo");
        assertThat(ref.url()).isEqualTo("https://hh.ru/vacancy/42");
    }

    // ─── helper ───────────────────────────────────────────────────────────────

    private static ReportSnapshot snapshot(int applied, int alreadyApplied, int skipped,
        int requiresTest, int applyTimeout, int applyTempError, int applyPermError, int dailyLimit) {
        return new ReportSnapshot(
            1L, START, END,
            applied, alreadyApplied, skipped, requiresTest,
            applyTimeout, applyTempError, applyPermError, 0,
            dailyLimit, List.of()
        );
    }
}
