package ru.hhassistant.domain.port;

import ru.hhassistant.domain.model.ReportSnapshot;
import ru.hhassistant.domain.model.VacancyDecision;
import ru.hhassistant.domain.model.VacancyProcessingState;
import ru.hhassistant.domain.model.VacancyStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface VacancyRepository {
  VacancyDecision tryClaim(
    long chatId,
    String vacancyId,
    String title,
    String employer,
    String url,
    String salaryText,
    int leaseMinutes,
    int retentionDays,
    Instant now
  );

  Map<String, ClaimPath> batchPeek(
    long chatId,
    List<String> vacancyIds,
    int leaseMinutes,
    int retentionDays,
    Instant now
  );

  void persistOutcome(
    long chatId,
    String vacancyId,
    VacancyStatus status,
    String lastError,           // null if without error
    Instant nextRetryAt,        // null if retry not needed
    Instant now
  );


  void upsertSkipped(
    long chatId,
    String vacancyId,
    String title,
    String employer,
    String url,
    String salaryText,
    VacancyStatus status,
    Instant now
  );

  int countAppliedToday(long chatId, Instant since);

  ReportSnapshot sessionStats(long chatId, Instant windowStart, Instant windowEnd, int dailyLimit);

  List<ReportSnapshot.TestVacancyRef> requiresTestInWindow(long chatId, Instant windowStart, int limit);

  int deleteAll(long chatId);

  Optional<VacancyProcessingState> findById(long chatId, String vacancyId);

  enum ClaimPath {
    TERMINAL,
    BACKOFF,
    IN_PROGRESS,
    CLAIMABLE
  }
}
