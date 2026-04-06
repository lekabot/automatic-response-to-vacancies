package ru.hhassistant.infrastructure.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import ru.hhassistant.domain.model.ReportSnapshot;
import ru.hhassistant.domain.model.VacancyDecision;
import ru.hhassistant.domain.model.VacancyProcessingState;
import ru.hhassistant.domain.model.VacancyStatus;
import ru.hhassistant.domain.port.VacancyRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ru.hhassistant.infrastructure.db.DbConverters.toInstant;
import static ru.hhassistant.infrastructure.db.DbConverters.toOdt;
import static ru.hhassistant.infrastructure.db.generated.Tables.VACANCIES_SEEN;

@ApplicationScoped
public class VacancyRepositoryImpl implements VacancyRepository {

    private static final int MAX_LAST_ERROR_LEN = 1000;

  @Inject
  DSLContext dsl;

  @Override
  public VacancyDecision tryClaim(
    long chatId, String vacancyId, String title, String employer,
    String url, String salaryText, int leaseMinutes, int retentionDays, Instant now
  ) {
    OffsetDateTime nowOdt = toOdt(now);
    OffsetDateTime retentionCutoff = toOdt(now.minusSeconds((long) retentionDays * 86400));
    OffsetDateTime leaseExpiry = nowOdt.minusMinutes(leaseMinutes);

    return dsl.transactionResult(ctx -> {
      var tx = DSL.using(ctx);

      Record row = tx
        .select()
        .from(VACANCIES_SEEN)
        .where(VACANCIES_SEEN.CHAT_ID.eq(chatId), VACANCIES_SEEN.VACANCY_ID.eq(vacancyId))
        .fetchOne();

      // Удаляем устаревшие записи
      if (row != null) {
        OffsetDateTime seenAt = row.get(VACANCIES_SEEN.SEEN_AT);
        if (seenAt != null && seenAt.isBefore(retentionCutoff)) {
          tx.deleteFrom(VACANCIES_SEEN)
            .where(VACANCIES_SEEN.CHAT_ID.eq(chatId), VACANCIES_SEEN.VACANCY_ID.eq(vacancyId))
            .execute();
          row = null;
        }
      }

      if (row != null) {
        String status = row.get(VACANCIES_SEEN.STATUS);
        VacancyStatus vs = VacancyStatus.valueOf(status);
        int attempts = row.get(VACANCIES_SEEN.ATTEMPT_COUNT);

        if (vs.isTerminal()) {
          return new VacancyDecision.SkipTerminal(vs, attempts);
        }

        if (vs == VacancyStatus.IN_PROGRESS) {
          OffsetDateTime started = row.get(VACANCIES_SEEN.PROCESSING_STARTED_AT);
          if (started != null && started.isAfter(leaseExpiry)) {
            return new VacancyDecision.SkipInProgress(
              toInstant(started.plusMinutes(leaseMinutes)), attempts);
          }
        }

        if (vs.isRetryable()) {
          OffsetDateTime nextRetry = row.get(VACANCIES_SEEN.NEXT_RETRY_AT);
          if (nextRetry != null && nextRetry.isAfter(nowOdt)) {
            return new VacancyDecision.SkipBackoff(toInstant(nextRetry), attempts, vs);
          }
        }

        // Клеймируем
        int newAttempts = attempts + 1;
        tx.update(VACANCIES_SEEN)
          .set(VACANCIES_SEEN.STATUS, VacancyStatus.IN_PROGRESS.name())
          .set(VACANCIES_SEEN.ATTEMPT_COUNT, newAttempts)
          .set(VACANCIES_SEEN.LAST_ATTEMPT_AT, nowOdt)
          .set(VACANCIES_SEEN.PROCESSING_STARTED_AT, nowOdt)
          .set(VACANCIES_SEEN.LAST_ERROR, (String) null)
          .set(VACANCIES_SEEN.NEXT_RETRY_AT, (OffsetDateTime) null)
          .set(VACANCIES_SEEN.TITLE, title)
          .set(VACANCIES_SEEN.EMPLOYER, employer)
          .set(VACANCIES_SEEN.URL, url)
          .set(VACANCIES_SEEN.SALARY_TEXT, salaryText)
          .set(VACANCIES_SEEN.SEEN_AT, nowOdt)
          .where(VACANCIES_SEEN.CHAT_ID.eq(chatId), VACANCIES_SEEN.VACANCY_ID.eq(vacancyId))
          .execute();
        return new VacancyDecision.Claimed(newAttempts);
      }

      // Новая вакансия — INSERT
      tx.insertInto(VACANCIES_SEEN)
        .set(VACANCIES_SEEN.CHAT_ID, chatId)
        .set(VACANCIES_SEEN.VACANCY_ID, vacancyId)
        .set(VACANCIES_SEEN.TITLE, title)
        .set(VACANCIES_SEEN.EMPLOYER, employer)
        .set(VACANCIES_SEEN.URL, url)
        .set(VACANCIES_SEEN.SALARY_TEXT, salaryText)
        .set(VACANCIES_SEEN.STATUS, VacancyStatus.IN_PROGRESS.name())
        .set(VACANCIES_SEEN.ATTEMPT_COUNT, 1)
        .set(VACANCIES_SEEN.LAST_ATTEMPT_AT, nowOdt)
        .set(VACANCIES_SEEN.PROCESSING_STARTED_AT, nowOdt)
        .set(VACANCIES_SEEN.SEEN_AT, nowOdt)
        .execute();
      return new VacancyDecision.Claimed(1);
    });
  }

  @Override
  public Map<String, ClaimPath> batchPeek(
    long chatId, List<String> vacancyIds, int leaseMinutes, int retentionDays, Instant now
  ) {
    if (vacancyIds.isEmpty()) return Map.of();
    OffsetDateTime nowOdt = toOdt(now);
    OffsetDateTime retentionCutoff = toOdt(now.minusSeconds((long) retentionDays * 86400));
    OffsetDateTime leaseExpiry = nowOdt.minusMinutes(leaseMinutes);

    var rows = dsl.select()
      .from(VACANCIES_SEEN)
      .where(VACANCIES_SEEN.CHAT_ID.eq(chatId), VACANCIES_SEEN.VACANCY_ID.in(vacancyIds))
      .fetch();

    Map<String, Record> byId = new HashMap<>();
    for (Record r : rows) byId.put(r.get(VACANCIES_SEEN.VACANCY_ID), r);

    Map<String, ClaimPath> result = new HashMap<>();
    for (String vid : vacancyIds) {
      Record row = byId.get(vid);
      if (row == null) {
        result.put(vid, ClaimPath.CLAIMABLE);
        continue;
      }

      OffsetDateTime seenAt = row.get(VACANCIES_SEEN.SEEN_AT);
      if (seenAt != null && seenAt.isBefore(retentionCutoff)) {
        result.put(vid, ClaimPath.CLAIMABLE);
        continue;
      }
      VacancyStatus vs = VacancyStatus.valueOf(row.get(VACANCIES_SEEN.STATUS));
      if (vs.isTerminal()) {
        result.put(vid, ClaimPath.TERMINAL);
        continue;
      }
      if (vs == VacancyStatus.IN_PROGRESS) {
        OffsetDateTime started = row.get(VACANCIES_SEEN.PROCESSING_STARTED_AT);
        if (started != null && started.isAfter(leaseExpiry)) {
          result.put(vid, ClaimPath.IN_PROGRESS);
          continue;
        }
      }
      if (vs.isRetryable()) {
        OffsetDateTime nextRetry = row.get(VACANCIES_SEEN.NEXT_RETRY_AT);
        if (nextRetry != null && nextRetry.isAfter(nowOdt)) {
          result.put(vid, ClaimPath.BACKOFF);
          continue;
        }
      }
      result.put(vid, ClaimPath.CLAIMABLE);
    }
    return result;
  }

  @Override
  public void persistOutcome(
    long chatId, String vacancyId, VacancyStatus status,
    String lastError, Instant nextRetryAt, Instant now
  ) {
    dsl.update(VACANCIES_SEEN)
      .set(VACANCIES_SEEN.STATUS, status.name())
      .set(VACANCIES_SEEN.LAST_ERROR, truncate(lastError, MAX_LAST_ERROR_LEN))
      .set(VACANCIES_SEEN.NEXT_RETRY_AT, nextRetryAt != null ? toOdt(nextRetryAt) : (OffsetDateTime) null)
      .set(VACANCIES_SEEN.PROCESSING_STARTED_AT, (OffsetDateTime) null)
      .set(VACANCIES_SEEN.SEEN_AT, toOdt(now))
      .where(VACANCIES_SEEN.CHAT_ID.eq(chatId), VACANCIES_SEEN.VACANCY_ID.eq(vacancyId))
      .execute();
  }

  @Override
  public void upsertSkipped(
    long chatId, String vacancyId, String title, String employer,
    String url, String salaryText, VacancyStatus status, Instant now
  ) {
    OffsetDateTime nowOdt = toOdt(now);
    dsl.insertInto(VACANCIES_SEEN)
      .set(VACANCIES_SEEN.CHAT_ID, chatId)
      .set(VACANCIES_SEEN.VACANCY_ID, vacancyId)
      .set(VACANCIES_SEEN.TITLE, title)
      .set(VACANCIES_SEEN.EMPLOYER, employer)
      .set(VACANCIES_SEEN.URL, url)
      .set(VACANCIES_SEEN.SALARY_TEXT, salaryText)
      .set(VACANCIES_SEEN.STATUS, status.name())
      .set(VACANCIES_SEEN.ATTEMPT_COUNT, 0)
      .set(VACANCIES_SEEN.SEEN_AT, nowOdt)
      .onConflict(VACANCIES_SEEN.CHAT_ID, VACANCIES_SEEN.VACANCY_ID)
      .doUpdate()
      .set(VACANCIES_SEEN.TITLE, title)
      .set(VACANCIES_SEEN.EMPLOYER, employer)
      .set(VACANCIES_SEEN.URL, url)
      .set(VACANCIES_SEEN.SALARY_TEXT, salaryText)
      .set(VACANCIES_SEEN.STATUS, status.name())
      .set(VACANCIES_SEEN.SEEN_AT, nowOdt)
      .set(VACANCIES_SEEN.PROCESSING_STARTED_AT, (OffsetDateTime) null)
      .set(VACANCIES_SEEN.NEXT_RETRY_AT, (OffsetDateTime) null)
      .execute();
  }

  @Override
  public int countAppliedToday(long chatId, Instant since) {
    return dsl.selectCount()
      .from(VACANCIES_SEEN)
      .where(VACANCIES_SEEN.CHAT_ID.eq(chatId),
        VACANCIES_SEEN.STATUS.eq(VacancyStatus.APPLIED.name()),
        VACANCIES_SEEN.SEEN_AT.ge(toOdt(since)))
      .fetchOne(0, Integer.class);
  }

  @Override
  public ReportSnapshot sessionStats(long chatId, Instant windowStart, Instant windowEnd, int dailyLimit) {
    var rows = dsl.select(VACANCIES_SEEN.STATUS, DSL.count())
      .from(VACANCIES_SEEN)
      .where(VACANCIES_SEEN.CHAT_ID.eq(chatId),
        VACANCIES_SEEN.SEEN_AT.ge(toOdt(windowStart)),
        VACANCIES_SEEN.SEEN_AT.lt(toOdt(windowEnd)))
      .groupBy(VACANCIES_SEEN.STATUS)
      .fetch();

    Map<String, Integer> counts = new HashMap<>();
    for (var r : rows) counts.put(r.get(VACANCIES_SEEN.STATUS), r.get(1, Integer.class));

    return new ReportSnapshot(chatId, windowStart, windowEnd,
      counts.getOrDefault(VacancyStatus.APPLIED.name(), 0),
      counts.getOrDefault(VacancyStatus.ALREADY_APPLIED.name(), 0),
      counts.getOrDefault(VacancyStatus.SKIPPED.name(), 0),
      counts.getOrDefault(VacancyStatus.REQUIRES_TEST.name(), 0),
      counts.getOrDefault(VacancyStatus.APPLY_TIMEOUT.name(), 0),
      counts.getOrDefault(VacancyStatus.APPLY_TEMP_ERROR.name(), 0),
      counts.getOrDefault(VacancyStatus.APPLY_PERM_ERROR.name(), 0),
      counts.getOrDefault(VacancyStatus.IN_PROGRESS.name(), 0),
      dailyLimit,
      List.of()
    );
  }

  @Override
  public List<ReportSnapshot.TestVacancyRef> requiresTestInWindow(
    long chatId, Instant windowStart, int limit
  ) {
    return dsl.select(VACANCIES_SEEN.TITLE, VACANCIES_SEEN.EMPLOYER, VACANCIES_SEEN.URL)
      .from(VACANCIES_SEEN)
      .where(VACANCIES_SEEN.CHAT_ID.eq(chatId),
        VACANCIES_SEEN.SEEN_AT.ge(toOdt(windowStart)),
        VACANCIES_SEEN.STATUS.eq(VacancyStatus.REQUIRES_TEST.name()))
      .orderBy(VACANCIES_SEEN.SEEN_AT.desc())
      .limit(limit)
      .fetch(r -> new ReportSnapshot.TestVacancyRef(
        r.get(VACANCIES_SEEN.TITLE), r.get(VACANCIES_SEEN.EMPLOYER), r.get(VACANCIES_SEEN.URL)));
  }

  @Override
  public int deleteAll(long chatId) {
    return dsl.deleteFrom(VACANCIES_SEEN)
      .where(VACANCIES_SEEN.CHAT_ID.eq(chatId))
      .execute();
  }

  @Override
  public Optional<VacancyProcessingState> findById(long chatId, String vacancyId) {
    Record row = dsl.select().from(VACANCIES_SEEN)
      .where(VACANCIES_SEEN.CHAT_ID.eq(chatId), VACANCIES_SEEN.VACANCY_ID.eq(vacancyId))
      .fetchOne();
    if (row == null) return Optional.empty();
    return Optional.of(mapToState(row));
  }

  // ─── mapping helpers ──────────────────────────────────────────────────────

  private VacancyProcessingState mapToState(Record r) {
    return new VacancyProcessingState(
      r.get(VACANCIES_SEEN.CHAT_ID),
      r.get(VACANCIES_SEEN.VACANCY_ID),
      r.get(VACANCIES_SEEN.TITLE),
      r.get(VACANCIES_SEEN.EMPLOYER),
      r.get(VACANCIES_SEEN.URL),
      r.get(VACANCIES_SEEN.SALARY_TEXT),
      VacancyStatus.valueOf(r.get(VACANCIES_SEEN.STATUS)),
      r.get(VACANCIES_SEEN.ATTEMPT_COUNT),
      r.get(VACANCIES_SEEN.LAST_ERROR),
      toInstant(r.get(VACANCIES_SEEN.LAST_ATTEMPT_AT)),
      toInstant(r.get(VACANCIES_SEEN.NEXT_RETRY_AT)),
      toInstant(r.get(VACANCIES_SEEN.PROCESSING_STARTED_AT)),
      toInstant(r.get(VACANCIES_SEEN.SEEN_AT))
    );
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  private static String truncate(String s, int maxLen) {
    if (s == null) return null;
    return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
  }
}
