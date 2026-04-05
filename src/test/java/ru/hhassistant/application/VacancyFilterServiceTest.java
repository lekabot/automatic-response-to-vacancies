package ru.hhassistant.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VacancyFilterServiceTest {

    @Test
    void tokenize_splitsOnWhitespace() {
        assertThat(VacancyFilterService.tokenize("Python разработчик"))
            .containsExactly("python", "разработчик");
    }

    @Test
    void tokenize_emptyString_returnsEmpty() {
        assertThat(VacancyFilterService.tokenize("")).isEmpty();
        assertThat(VacancyFilterService.tokenize(null)).isEmpty();
    }

    @Test
    void tokenize_multipleSpaces_handledCorrectly() {
        assertThat(VacancyFilterService.tokenize("  Python   backend  "))
            .containsExactly("python", "backend");
    }

    // ─── matchesExclude ───────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "Junior Python developer, junior, true",
        "Senior Python developer, junior, false",
        "1C разработчик, 1C, true",
        "Python Backend Engineer, стажёр, false",
    })
    void matchesExclude_correctlyIdentifiesMatches(String title, String exclude, boolean expected) {
        VacancyCandidate candidate = candidate("v1", title, "ACME");
        assertThat(candidate.matchesExclude(List.of(exclude))).isEqualTo(expected);
    }

    @Test
    void matchesExclude_emptyList_alwaysFalse() {
        VacancyCandidate candidate = candidate("v1", "Junior Python", "ACME");
        assertThat(candidate.matchesExclude(List.of())).isFalse();
    }

    @Test
    void matchesExclude_matchInEmployerName() {
        VacancyCandidate candidate = candidate("v1", "Python developer", "Junior Corp");
        assertThat(candidate.matchesExclude(List.of("junior corp"))).isTrue();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static VacancyCandidate candidate(String id, String title, String employer) {
        return new VacancyCandidate(id, title, employer, "https://hh.ru/vacancy/" + id,
            null, false, "Москва");
    }
}
