package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.domain.model.UserSearchConfig;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.util.*;

@ApplicationScoped
@Slf4j
public class VacancyFilterService {
  @Inject
  MeterRegistry meterRegistry;

  public sealed interface FilterResult {
    record Relevant(VacancyCandidate vacancy) implements FilterResult {
    }

    record Irrelevant(VacancyCandidate vacancy, List<String> missingTokens) implements FilterResult {
    }

    record ExcludedByKeyword(VacancyCandidate vacancy, String matchedExclude) implements FilterResult {
    }

    record HasTest(VacancyCandidate vacancy) implements FilterResult {
    }
  }

  public List<VacancyCandidate> filterForKeyword(String keyword, List<VacancyCandidate> candidates, UserSearchConfig config, Set<String> seenIds) {
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
          log.debug("Filtered irrelevant: vacancyId={} title={} missingTokens={}",
            i.vacancy().vacancyId(), i.vacancy().title(), i.missingTokens());
        }
        case FilterResult.ExcludedByKeyword e -> log.debug("Excluded by keyword '{}': vacancyId={}",
          e.matchedExclude(), e.vacancy().vacancyId());
        case FilterResult.HasTest h -> result.add(h.vacancy());
      }
    }
    return result;
  }

  public List<VacancyCandidate> mergeAndFilter(Map<String, List<VacancyCandidate>> byKeyword, UserSearchConfig config) {
    Set<String> seenIds = new LinkedHashSet<>();
    List<VacancyCandidate> merged = new ArrayList<>();
    for (var entry : byKeyword.entrySet()) {
      merged.addAll(filterForKeyword(entry.getKey(), entry.getValue(), config, seenIds));
      if (merged.size() >= config.maxVacanciesPerRun()) break;
    }
    int cap = config.maxVacanciesPerRun();
    return merged.size() > cap ? merged.subList(0, cap) : merged;
  }

  private FilterResult evaluate(VacancyCandidate vacancy, List<String> queryTokens, List<String> excludeKeywords) {
    if (!excludeKeywords.isEmpty()) {
      String ex = findExcludeMatch(vacancy, excludeKeywords);
      if (ex != null) return new FilterResult.ExcludedByKeyword(vacancy, ex);
    }

    if (vacancy.hasTest()) return new FilterResult.HasTest(vacancy);

    String searchText = (vacancy.title() + " " + vacancy.employer()).toLowerCase();
    List<String> missing = queryTokens.stream()
      .filter(t -> !searchText.contains(t))
      .toList();
    if (!missing.isEmpty()) return new FilterResult.Irrelevant(vacancy, missing);

    return new FilterResult.Relevant(vacancy);
  }

  private String findExcludeMatch(VacancyCandidate vacancy, List<String> excludeKeywords) {
    var titleLow = vacancy.title().toLowerCase();
    var employerLow = vacancy.employer() != null ? vacancy.employer().toLowerCase() : "";
    for (var kw : excludeKeywords) {
      var kwLow = kw.trim().toLowerCase();
      if (!kwLow.isEmpty() && (titleLow.contains(kwLow) || employerLow.contains(kwLow))) return kw;
    }
    return null;
  }

  static List<String> tokenize(String text) {
    if (text == null || text.isBlank()) return List.of();
    return Arrays.stream(text.trim().toLowerCase().split("\\s+"))
      .filter(t -> !t.isBlank())
      .toList();
  }
}
