package ru.hhassistant.domain.model;

import java.util.List;
import java.util.Optional;

public record UserSearchConfig(
  long chatId,
  String resumeId,
  String resumeTitle,
  List<String> keywords,
  String coverLetterTemplate,   // null is without letter
  String hhtoken,               // null is not authorized
  List<String> excludeKeywords,
  List<Integer> searchAreas,
  List<String> schedules,
  List<String> employmentTypes,
  Optional<String> searchField, // empty is default
  int dailyApplyLimit,
  int publishedWithinHours,
  int maxVacanciesPerRun,
  int retentionDays,
  int leaseMinutes,
  double pollIntervalSeconds,
  double pollIntervalMaxSeconds,
  boolean sameResultBackoffEnabled,
  int applyTotalTimeoutSeconds,
  int applyPerAttemptTimeoutSeconds
) {

  public boolean isComplete() {
    return resumeId != null && !resumeId.isBlank()
      && !keywords.isEmpty();
  }

  public int publishedWithinDays() {
    return Math.max(1, publishedWithinHours / 24);
  }
}
