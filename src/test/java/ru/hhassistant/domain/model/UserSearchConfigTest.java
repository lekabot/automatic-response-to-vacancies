package ru.hhassistant.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UserSearchConfigTest {

    // ─── isComplete ───────────────────────────────────────────────────────────

    @Test
    void isComplete_fullConfig_returnsTrue() {
        var config = config("resume-abc", List.of("python"), "token");
        assertThat(config.isComplete()).isTrue();
    }

    @Test
    void isComplete_nullResumeId_returnsFalse() {
        var config = config(null, List.of("python"), "token");
        assertThat(config.isComplete()).isFalse();
    }

    @Test
    void isComplete_blankResumeId_returnsFalse() {
        var config = config("  ", List.of("python"), "token");
        assertThat(config.isComplete()).isFalse();
    }

    @Test
    void isComplete_emptyKeywords_returnsFalse() {
        var config = config("resume-abc", List.of(), "token");
        assertThat(config.isComplete()).isFalse();
    }

    // ─── publishedWithinDays ──────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "0,  1",   // 0 hours → floor to 1 day
        "23, 1",   // 23h < 1 day → rounds down to 0, clamped to 1
        "24, 1",   // exactly 1 day
        "48, 2",   // 2 days
        "72, 3",   // 3 days
    })
    void publishedWithinDays_calculatesCorrectly(int hours, int expectedDays) {
        var config = configWithHours(hours);
        assertThat(config.publishedWithinDays()).isEqualTo(expectedDays);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static UserSearchConfig config(String resumeId, List<String> keywords, String hhtoken) {
        return new UserSearchConfig(
            1L, resumeId, "My Resume", keywords,
            null, hhtoken, List.of(), List.of(1), List.of(), List.of(),
            Optional.empty(), 50, 24, 100, 30, 10,
            60.0, 300.0, false, 30, 15
        );
    }

    private static UserSearchConfig configWithHours(int hours) {
        return new UserSearchConfig(
            1L, "resume-abc", "My Resume", List.of("python"),
            null, "token", List.of(), List.of(1), List.of(), List.of(),
            Optional.empty(), 50, hours, 100, 30, 10,
            60.0, 300.0, false, 30, 15
        );
    }
}
