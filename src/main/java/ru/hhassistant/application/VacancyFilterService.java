package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import ru.hhassistant.domain.model.UserSearchConfig;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Фильтрация вакансий по релевантности запросу.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>Разбить keyword-запрос на токены (split by whitespace).</li>
 *   <li>Проверить, что все обязательные токены присутствуют в title + employer вакансии.</li>
 *   <li>Исключить вакансии, содержащие exclude-keywords в названии.</li>
 *   <li>Исключить вакансии с тестовыми заданиями (по флагу из API).</li>
 * </ol>
 *
 * <p>Метрика {@code hh.vacancies.filtered_irrelevant} считает отфильтрованные вакансии.
 */
@ApplicationScoped
public class VacancyFilterService {

    private static final Logger log = Logger.getLogger(VacancyFilterService.class);

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Результат фильтрации одной вакансии для одного keyword-запроса.
     */
    public sealed interface FilterResult {
        record Relevant(VacancyCandidate vacancy) implements FilterResult {}
        record Irrelevant(VacancyCandidate vacancy, List<String> missingTokens) implements FilterResult {}
        record ExcludedByKeyword(VacancyCandidate vacancy, String matchedExclude) implements FilterResult {}
        record HasTest(VacancyCandidate vacancy) implements FilterResult {}
    }

    /**
     * Фильтрует список вакансий для заданного keyword-запроса.
     * Дедуплицирует по vacancyId через seenIds.
     *
     * @return список релевантных вакансий (SKIPPED/REQUIRES_TEST обрабатываются отдельно)
     */
    public List<VacancyCandidate> filterForKeyword(
        String keyword,
        List<VacancyCandidate> candidates,
        UserSearchConfig config,
        Set<String> seenIds
    ) {
        List<String> queryTokens = tokenize(keyword);
        List<VacancyCandidate> result = new ArrayList<>();

        for (VacancyCandidate candidate : candidates) {
            if (seenIds.contains(candidate.vacancyId())) continue;

            FilterResult fr = evaluate(candidate, queryTokens, config.excludeKeywords());
            switch (fr) {
                case FilterResult.Relevant r -> {
                    seenIds.add(r.vacancy().vacancyId());
                    result.add(r.vacancy());
                }
                case FilterResult.Irrelevant i -> {
                    meterRegistry.counter("hh.vacancies.filtered_irrelevant",
                        "keyword", keyword).increment();
                    log.debugf("Filtered irrelevant: vacancyId=%s title=%s missingTokens=%s",
                        i.vacancy().vacancyId(), i.vacancy().title(), i.missingTokens());
                }
                case FilterResult.ExcludedByKeyword e ->
                    log.debugf("Excluded by keyword '%s': vacancyId=%s",
                        e.matchedExclude(), e.vacancy().vacancyId());
                case FilterResult.HasTest h ->
                    // has_test вакансии попадают в основной цикл для записи REQUIRES_TEST
                    result.add(h.vacancy());
            }
        }
        return result;
    }

    /**
     * Де-дуплицирует и объединяет результаты по нескольким keyword-запросам.
     */
    public List<VacancyCandidate> mergeAndFilter(
        java.util.Map<String, List<VacancyCandidate>> byKeyword,
        UserSearchConfig config
    ) {
        Set<String> seenIds = new LinkedHashSet<>();
        List<VacancyCandidate> merged = new ArrayList<>();
        for (var entry : byKeyword.entrySet()) {
            merged.addAll(filterForKeyword(entry.getKey(), entry.getValue(), config, seenIds));
            if (merged.size() >= config.maxVacanciesPerRun()) break;
        }
        int cap = config.maxVacanciesPerRun();
        return merged.size() > cap ? merged.subList(0, cap) : merged;
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private FilterResult evaluate(
        VacancyCandidate vacancy,
        List<String> queryTokens,
        List<String> excludeKeywords
    ) {
        // Exclude по ключевым словам (проверяется до relevance, чтобы быстрее)
        if (!excludeKeywords.isEmpty()) {
            String ex = findExcludeMatch(vacancy, excludeKeywords);
            if (ex != null) return new FilterResult.ExcludedByKeyword(vacancy, ex);
        }

        // Тестовое задание — пропускаем в REQUIRES_TEST
        if (vacancy.hasTest()) return new FilterResult.HasTest(vacancy);

        // Проверка релевантности
        String searchText = (vacancy.title() + " " + vacancy.employer()).toLowerCase();
        List<String> missing = queryTokens.stream()
            .filter(t -> !searchText.contains(t))
            .toList();
        if (!missing.isEmpty()) return new FilterResult.Irrelevant(vacancy, missing);

        return new FilterResult.Relevant(vacancy);
    }

    private String findExcludeMatch(VacancyCandidate vacancy, List<String> excludeKeywords) {
        String titleLow = vacancy.title().toLowerCase();
        for (String kw : excludeKeywords) {
            if (!kw.isBlank() && titleLow.contains(kw.trim().toLowerCase())) return kw;
        }
        return null;
    }

    /** Разбивает строку на токены по пробелу, lowercase, убирает пустые. */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.trim().toLowerCase().split("\\s+"))
            .filter(t -> !t.isBlank())
            .toList();
    }
}
