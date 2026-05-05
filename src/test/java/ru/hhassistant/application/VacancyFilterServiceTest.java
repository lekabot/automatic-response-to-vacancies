package ru.hhassistant.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.hhassistant.domain.model.UserSearchConfig;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class VacancyFilterServiceTest {

    private VacancyFilterService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new VacancyFilterService();
        Field field = VacancyFilterService.class.getDeclaredField("meterRegistry");
        field.setAccessible(true);
        field.set(service, new SimpleMeterRegistry());
    }

    // ─── tokenize ─────────────────────────────────────────────────────────────

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

    // ─── filterForKeyword ─────────────────────────────────────────────────────

    @Test
    void filterForKeyword_relevantVacancy_included() {
        var candidates = List.of(candidate("v1", "Java разработчик Backend", "ACME"));
        var result = service.filterForKeyword("java разработчик", candidates, config(List.of(), 100), new HashSet<>());
        assertThat(result).extracting(VacancyCandidate::vacancyId).containsExactly("v1");
    }

    @Test
    void filterForKeyword_irrelevantVacancy_excluded() {
        var candidates = List.of(candidate("v1", "Python разработчик", "ACME"));
        var result = service.filterForKeyword("java разработчик", candidates, config(List.of(), 100), new HashSet<>());
        assertThat(result).isEmpty();
    }

    @Test
    void filterForKeyword_excludedByKeywordInTitle_filtered() {
        var candidates = List.of(
            candidate("v1", "Junior Java разработчик", "ACME"),
            candidate("v2", "Senior Java разработчик", "ACME")
        );
        var result = service.filterForKeyword("java разработчик", candidates, config(List.of("junior"), 100), new HashSet<>());
        assertThat(result).extracting(VacancyCandidate::vacancyId).containsExactly("v2");
    }

    @Test
    void filterForKeyword_excludedByKeywordInEmployer_filtered() {
        // MA-1 fix: employer name is now also checked against exclude keywords
        var candidates = List.of(
            candidate("v1", "Java разработчик", "Junior Corp"),
            candidate("v2", "Java разработчик", "Senior LLC")
        );
        var result = service.filterForKeyword("java разработчик", candidates, config(List.of("junior corp"), 100), new HashSet<>());
        assertThat(result).extracting(VacancyCandidate::vacancyId).containsExactly("v2");
    }

    @Test
    void filterForKeyword_hasTestVacancy_passedThrough() {
        // hasTest=true вакансии проходят без проверки relevancy, чтобы показать пользователю
        var candidate = new VacancyCandidate("v1", "Python Dev", "ACME", "https://hh.ru/vacancy/v1",
            null, true, "Москва");
        var result = service.filterForKeyword("java разработчик", List.of(candidate), config(List.of(), 100), new HashSet<>());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).hasTest()).isTrue();
    }

    @Test
    void filterForKeyword_alreadySeenId_deduped() {
        var seen = new HashSet<>(Set.of("v1"));
        var candidates = List.of(candidate("v1", "Java разработчик", "ACME"));
        var result = service.filterForKeyword("java разработчик", candidates, config(List.of(), 100), seen);
        assertThat(result).isEmpty();
    }

    @Test
    void filterForKeyword_addsSeen_acrossMultipleCalls() {
        Set<String> seen = new HashSet<>();
        var candidates = List.of(candidate("v1", "Java разработчик", "ACME"));
        service.filterForKeyword("java разработчик", candidates, config(List.of(), 100), seen);
        assertThat(seen).contains("v1");

        // second call with same seen set — vacancy already seen, deduped
        var result2 = service.filterForKeyword("java разработчик", candidates, config(List.of(), 100), seen);
        assertThat(result2).isEmpty();
    }

    @Test
    void filterForKeyword_nullEmployer_doesNotThrow() {
        var candidate = new VacancyCandidate("v1", "Java разработчик", null, "https://hh.ru/vacancy/v1",
            null, false, "Москва");
        var result = service.filterForKeyword("java разработчик", List.of(candidate), config(List.of("acme"), 100), new HashSet<>());
        assertThat(result).hasSize(1);
    }

    // ─── mergeAndFilter ───────────────────────────────────────────────────────

    @Test
    void mergeAndFilter_multipleKeywords_deduplicatesAcrossKeywords() {
        var shared = candidate("shared-v1", "Java Backend разработчик", "ACME");
        Map<String, List<VacancyCandidate>> byKeyword = new LinkedHashMap<>();
        byKeyword.put("java разработчик", List.of(shared, candidate("v2", "Java разработчик", "BetaCo")));
        byKeyword.put("backend разработчик", List.of(shared, candidate("v3", "Backend разработчик", "GammaCo")));

        var result = service.mergeAndFilter(byKeyword, config(List.of(), 100));
        assertThat(result).extracting(VacancyCandidate::vacancyId)
            .containsExactlyInAnyOrder("shared-v1", "v2", "v3")
            .doesNotHaveDuplicates();
    }

    @Test
    void mergeAndFilter_cappedAtMaxVacanciesPerRun() {
        var candidates = new ArrayList<VacancyCandidate>();
        for (int i = 0; i < 10; i++) {
            candidates.add(candidate("v" + i, "Java разработчик " + i, "ACME"));
        }
        Map<String, List<VacancyCandidate>> byKeyword = Map.of("java разработчик", candidates);

        var result = service.mergeAndFilter(byKeyword, config(List.of(), 3));
        assertThat(result).hasSize(3);
    }

    @Test
    void mergeAndFilter_emptyInput_returnsEmpty() {
        var result = service.mergeAndFilter(Map.of(), config(List.of(), 100));
        assertThat(result).isEmpty();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static VacancyCandidate candidate(String id, String title, String employer) {
        return new VacancyCandidate(id, title, employer, "https://hh.ru/vacancy/" + id,
            null, false, "Москва");
    }

    private static UserSearchConfig config(List<String> excludeKeywords, int maxVacancies) {
        return new UserSearchConfig(
            1L, "resume-abc", "My Resume", List.of("java"),
            null, "hhtoken", excludeKeywords, List.of(1), List.of(), List.of(),
            Optional.empty(), 50, 24, maxVacancies, 30, 10,
            60.0, 300.0, false, 30, 15
        );
    }
}
