package ru.hhassistant.domain.model;

import java.time.Instant;
import java.util.List;


public record ReportSnapshot(
  long chatId,
  Instant windowStart,
  Instant windowEnd,
  int applied,
  int alreadyApplied,
  int skipped,
  int requiresTest,
  int applyTimeout,
  int applyTempError,
  int applyPermError,
  int inProgress,
  int dailyLimit,
  List<TestVacancyRef> requiresTestVacancies
) {

  public int totalProcessed() {
    return applied + alreadyApplied + skipped + requiresTest + applyTimeout + applyTempError + applyPermError;
  }

  public int retryLater() {
    return applyTimeout + applyTempError;
  }

  public boolean limitReached() {
    return applied >= dailyLimit;
  }

  public record TestVacancyRef(String title, String employer, String url) {
  }

  public ReportSnapshot withRequiresTestVacancies(List<TestVacancyRef> refs) {
    return new ReportSnapshot(chatId, windowStart, windowEnd,
      applied, alreadyApplied, skipped, requiresTest,
      applyTimeout, applyTempError, applyPermError, inProgress,
      dailyLimit, refs);
  }
}
