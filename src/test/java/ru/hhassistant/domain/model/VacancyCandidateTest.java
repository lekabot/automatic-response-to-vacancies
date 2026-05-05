package ru.hhassistant.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VacancyCandidateTest {

    // ─── matchesExclude ───────────────────────────────────────────────────────

    @Test
    void matchesExclude_keywordInTitle_true() {
        var c = candidate("v1", "Junior Java Developer", "ACME");
        assertThat(c.matchesExclude(List.of("junior"))).isTrue();
    }

    @Test
    void matchesExclude_keywordInEmployer_true() {
        var c = candidate("v1", "Java Developer", "Junior Corp");
        assertThat(c.matchesExclude(List.of("junior corp"))).isTrue();
    }

    @Test
    void matchesExclude_keywordAbsent_false() {
        var c = candidate("v1", "Senior Java Developer", "ACME");
        assertThat(c.matchesExclude(List.of("junior", "стажёр"))).isFalse();
    }

    @Test
    void matchesExclude_emptyKeywordList_false() {
        var c = candidate("v1", "Junior Developer", "ACME");
        assertThat(c.matchesExclude(List.of())).isFalse();
    }

    @Test
    void matchesExclude_caseInsensitive() {
        var c = candidate("v1", "JUNIOR developer", "ACME");
        assertThat(c.matchesExclude(List.of("Junior"))).isTrue();
    }

    @Test
    void matchesExclude_nullEmployer_noNpe() {
        var c = new VacancyCandidate("v1", "Java Developer", null, "url", null, false, "Москва");
        assertThat(c.matchesExclude(List.of("acme"))).isFalse();
    }

    @Test
    void matchesExclude_blankKeyword_ignored() {
        var c = candidate("v1", "Junior Developer", "ACME");
        assertThat(c.matchesExclude(List.of("   ", ""))).isFalse();
    }

    // ─── displaySalary ────────────────────────────────────────────────────────

    @Test
    void displaySalary_withValue_returnsSalary() {
        var c = new VacancyCandidate("v1", "Java Dev", "ACME", "url", "150 000 руб.", false, "Москва");
        assertThat(c.displaySalary()).isEqualTo("150 000 руб.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void displaySalary_nullOrBlank_returnsNotSpecified(String salaryText) {
        var c = new VacancyCandidate("v1", "Java Dev", "ACME", "url", salaryText, false, "Москва");
        assertThat(c.displaySalary()).isEqualTo("з/п не указана");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static VacancyCandidate candidate(String id, String title, String employer) {
        return new VacancyCandidate(id, title, employer, "https://hh.ru/vacancy/" + id, null, false, "Москва");
    }
}
