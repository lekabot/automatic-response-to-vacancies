package ru.hhassistant.infrastructure.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jboss.logging.Logger;
import ru.hhassistant.domain.model.*;
import ru.hhassistant.domain.port.VacancyRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ru.hhassistant.infrastructure.db.Tables.*;

/**
 * jOOQ-реализация {@link VacancyRepository} для PostgreSQL.
 *
 * <p>Все запросы выполняются с явным SQL через DSL.
 * Нет Hibernate, нет ORM-маппинга, нет lazy loading.
 */
@ApplicationScoped
public class VacancyRepositoryImpl implements VacancyRepository {

    private static final Logger log = Logger.getLogger(VacancyRepositoryImpl.class);
    private static final int MAX_LAST_ERROR_LEN = 1000;

    @Inject DSLContext dsl;

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
                .where(VS_CHAT_ID.eq(chatId), VS_VACANCY_ID.eq(vacancyId))
                .fetchOne();

            // Удаляем устаревшие записи
            if (row != null) {
                OffsetDateTime seenAt = row.get(VS_SEEN_AT);
                if (seenAt != null && seenAt.isBefore(retentionCutoff)) {
                    tx.deleteFrom(VACANCIES_SEEN)
                        .where(VS_CHAT_ID.eq(chatId), VS_VACANCY_ID.eq(vacancyId))
                        .execute();
                    row = null;
                }
            }

            if (row != null) {
                String status = row.get(VS_STATUS);
                VacancyStatus vs = VacancyStatus.valueOf(status);
                int attempts = row.get(VS_ATTEMPT_COUNT);

                if (vs.isTerminal()) {
                    return new VacancyDecision.SkipTerminal(vs, attempts);
                }

                if (vs == VacancyStatus.IN_PROGRESS) {
                    OffsetDateTime started = row.get(VS_PROCESSING_STARTED_AT);
                    if (started != null && started.isAfter(leaseExpiry)) {
                        return new VacancyDecision.SkipInProgress(
                            toInstant(started.plusMinutes(leaseMinutes)), attempts);
                    }
                }

                if (vs.isRetryable()) {
                    OffsetDateTime nextRetry = row.get(VS_NEXT_RETRY_AT);
                    if (nextRetry != null && nextRetry.isAfter(nowOdt)) {
                        return new VacancyDecision.SkipBackoff(toInstant(nextRetry), attempts, vs);
                    }
                }

                // Клеймируем
                int newAttempts = attempts + 1;
                tx.update(VACANCIES_SEEN)
                    .set(VS_STATUS, VacancyStatus.IN_PROGRESS.name())
                    .set(VS_ATTEMPT_COUNT, newAttempts)
                    .set(VS_LAST_ATTEMPT_AT, nowOdt)
                    .set(VS_PROCESSING_STARTED_AT, nowOdt)
                    .set(VS_LAST_ERROR, (String) null)
                    .set(VS_NEXT_RETRY_AT, (OffsetDateTime) null)
                    .set(VS_TITLE, title)
                    .set(VS_EMPLOYER, employer)
                    .set(VS_URL, url)
                    .set(VS_SALARY_TEXT, salaryText)
                    .set(VS_SEEN_AT, nowOdt)
                    .where(VS_CHAT_ID.eq(chatId), VS_VACANCY_ID.eq(vacancyId))
                    .execute();
                return new VacancyDecision.Claimed(newAttempts);
            }

            // Новая вакансия — INSERT
            tx.insertInto(VACANCIES_SEEN)
                .set(VS_CHAT_ID, chatId)
                .set(VS_VACANCY_ID, vacancyId)
                .set(VS_TITLE, title)
                .set(VS_EMPLOYER, employer)
                .set(VS_URL, url)
                .set(VS_SALARY_TEXT, salaryText)
                .set(VS_STATUS, VacancyStatus.IN_PROGRESS.name())
                .set(VS_ATTEMPT_COUNT, 1)
                .set(VS_LAST_ATTEMPT_AT, nowOdt)
                .set(VS_PROCESSING_STARTED_AT, nowOdt)
                .set(VS_SEEN_AT, nowOdt)
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
            .where(VS_CHAT_ID.eq(chatId), VS_VACANCY_ID.in(vacancyIds))
            .fetch();

        Map<String, Record> byId = new java.util.HashMap<>();
        for (Record r : rows) byId.put(r.get(VS_VACANCY_ID), r);

        Map<String, ClaimPath> result = new java.util.HashMap<>();
        for (String vid : vacancyIds) {
            Record row = byId.get(vid);
            if (row == null) { result.put(vid, ClaimPath.CLAIMABLE); continue; }

            OffsetDateTime seenAt = row.get(VS_SEEN_AT);
            if (seenAt != null && seenAt.isBefore(retentionCutoff)) {
                result.put(vid, ClaimPath.CLAIMABLE); continue;
            }
            VacancyStatus vs = VacancyStatus.valueOf(row.get(VS_STATUS));
            if (vs.isTerminal()) { result.put(vid, ClaimPath.TERMINAL); continue; }
            if (vs == VacancyStatus.IN_PROGRESS) {
                OffsetDateTime started = row.get(VS_PROCESSING_STARTED_AT);
                if (started != null && started.isAfter(leaseExpiry)) {
                    result.put(vid, ClaimPath.IN_PROGRESS); continue;
                }
            }
            if (vs.isRetryable()) {
                OffsetDateTime nextRetry = row.get(VS_NEXT_RETRY_AT);
                if (nextRetry != null && nextRetry.isAfter(nowOdt)) {
                    result.put(vid, ClaimPath.BACKOFF); continue;
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
            .set(VS_STATUS, status.name())
            .set(VS_LAST_ERROR, truncate(lastError, MAX_LAST_ERROR_LEN))
            .set(VS_NEXT_RETRY_AT, nextRetryAt != null ? toOdt(nextRetryAt) : (OffsetDateTime) null)
            .set(VS_PROCESSING_STARTED_AT, (OffsetDateTime) null)
            .set(VS_SEEN_AT, toOdt(now))
            .where(VS_CHAT_ID.eq(chatId), VS_VACANCY_ID.eq(vacancyId))
            .execute();
    }

    @Override
    public void upsertSkipped(
        long chatId, String vacancyId, String title, String employer,
        String url, String salaryText, VacancyStatus status, Instant now
    ) {
        OffsetDateTime nowOdt = toOdt(now);
        dsl.insertInto(VACANCIES_SEEN)
            .set(VS_CHAT_ID, chatId)
            .set(VS_VACANCY_ID, vacancyId)
            .set(VS_TITLE, title)
            .set(VS_EMPLOYER, employer)
            .set(VS_URL, url)
            .set(VS_SALARY_TEXT, salaryText)
            .set(VS_STATUS, status.name())
            .set(VS_ATTEMPT_COUNT, 0)
            .set(VS_SEEN_AT, nowOdt)
            .onConflict(VS_CHAT_ID, VS_VACANCY_ID)
            .doUpdate()
            .set(VS_TITLE, title).set(VS_EMPLOYER, employer).set(VS_URL, url)
            .set(VS_SALARY_TEXT, salaryText).set(VS_STATUS, status.name())
            .set(VS_SEEN_AT, nowOdt)
            .set(VS_PROCESSING_STARTED_AT, (OffsetDateTime) null)
            .set(VS_NEXT_RETRY_AT, (OffsetDateTime) null)
            .execute();
    }

    @Override
    public int countAppliedToday(long chatId, Instant since) {
        return dsl.selectCount()
            .from(VACANCIES_SEEN)
            .where(VS_CHAT_ID.eq(chatId),
                VS_STATUS.eq(VacancyStatus.APPLIED.name()),
                VS_SEEN_AT.ge(toOdt(since)))
            .fetchOne(0, Integer.class);
    }

    @Override
    public ReportSnapshot sessionStats(long chatId, Instant windowStart, Instant windowEnd, int dailyLimit) {
        var rows = dsl.select(VS_STATUS, DSL.count())
            .from(VACANCIES_SEEN)
            .where(VS_CHAT_ID.eq(chatId),
                VS_SEEN_AT.ge(toOdt(windowStart)),
                VS_SEEN_AT.lt(toOdt(windowEnd)))
            .groupBy(VS_STATUS)
            .fetch();

        Map<String, Integer> counts = new java.util.HashMap<>();
        for (var r : rows) counts.put(r.get(VS_STATUS), r.get(1, Integer.class));

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
        return dsl.select(VS_TITLE, VS_EMPLOYER, VS_URL)
            .from(VACANCIES_SEEN)
            .where(VS_CHAT_ID.eq(chatId),
                VS_SEEN_AT.ge(toOdt(windowStart)),
                VS_STATUS.eq(VacancyStatus.REQUIRES_TEST.name()))
            .orderBy(VS_SEEN_AT.desc())
            .limit(limit)
            .fetch(r -> new ReportSnapshot.TestVacancyRef(
                r.get(VS_TITLE), r.get(VS_EMPLOYER), r.get(VS_URL)));
    }

    @Override
    public int deleteAll(long chatId) {
        return dsl.deleteFrom(VACANCIES_SEEN)
            .where(VS_CHAT_ID.eq(chatId))
            .execute();
    }

    @Override
    public Optional<VacancyProcessingState> findById(long chatId, String vacancyId) {
        Record row = dsl.select().from(VACANCIES_SEEN)
            .where(VS_CHAT_ID.eq(chatId), VS_VACANCY_ID.eq(vacancyId))
            .fetchOne();
        if (row == null) return Optional.empty();
        return Optional.of(mapToState(row));
    }

    // ─── mapping helpers ──────────────────────────────────────────────────────

    private VacancyProcessingState mapToState(Record r) {
        return new VacancyProcessingState(
            r.get(VS_CHAT_ID),
            r.get(VS_VACANCY_ID),
            r.get(VS_TITLE),
            r.get(VS_EMPLOYER),
            r.get(VS_URL),
            r.get(VS_SALARY_TEXT),
            VacancyStatus.valueOf(r.get(VS_STATUS)),
            r.get(VS_ATTEMPT_COUNT),
            r.get(VS_LAST_ERROR),
            toInstant(r.get(VS_LAST_ATTEMPT_AT)),
            toInstant(r.get(VS_NEXT_RETRY_AT)),
            toInstant(r.get(VS_PROCESSING_STARTED_AT)),
            toInstant(r.get(VS_SEEN_AT))
        );
    }

    // ─── converters ───────────────────────────────────────────────────────────

    static OffsetDateTime toOdt(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
