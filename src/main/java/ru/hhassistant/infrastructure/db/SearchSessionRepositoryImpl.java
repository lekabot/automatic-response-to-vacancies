package ru.hhassistant.infrastructure.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jboss.logging.Logger;
import ru.hhassistant.domain.model.SearchSession;
import ru.hhassistant.domain.port.SearchSessionRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ru.hhassistant.infrastructure.db.Tables.*;
import static ru.hhassistant.infrastructure.db.VacancyRepositoryImpl.*;

/**
 * jOOQ-реализация {@link SearchSessionRepository}.
 * Состояние сессии хранится в таблице user_settings (колонки search_session_started_at,
 * last_hourly_report_slot) — не нужна отдельная таблица для минимальной схемы.
 */
@ApplicationScoped
public class SearchSessionRepositoryImpl implements SearchSessionRepository {

    private static final Logger log = Logger.getLogger(SearchSessionRepositoryImpl.class);

    @Inject DSLContext dsl;

    @Override
    public void start(long chatId, Instant startedAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.insertInto(USER_SETTINGS)
            .set(US_CHAT_ID, chatId)
            .set(US_SESSION_STARTED_AT, toOdt(startedAt))
            .set(US_LAST_HOURLY_SLOT, (Integer) null)
            .set(US_UPDATED_AT, now)
            .set(US_KEYWORDS_JSON, "[]")
            .onConflict(US_CHAT_ID)
            .doUpdate()
            .set(US_SESSION_STARTED_AT, toOdt(startedAt))
            .set(US_LAST_HOURLY_SLOT, (Integer) null)
            .set(US_UPDATED_AT, now)
            .execute();
    }

    @Override
    public boolean clear(long chatId) {
        int updated = dsl.update(USER_SETTINGS)
            .set(US_SESSION_STARTED_AT, (OffsetDateTime) null)
            .set(US_LAST_HOURLY_SLOT, (Integer) null)
            .set(US_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(US_CHAT_ID.eq(chatId),
                US_SESSION_STARTED_AT.isNotNull()
                    .or(US_LAST_HOURLY_SLOT.isNotNull()))
            .execute();
        return updated > 0;
    }

    @Override
    public Optional<SearchSession> find(long chatId) {
        Record row = dsl.select(US_CHAT_ID, US_SESSION_STARTED_AT, US_LAST_HOURLY_SLOT)
            .from(USER_SETTINGS)
            .where(US_CHAT_ID.eq(chatId), US_SESSION_STARTED_AT.isNotNull())
            .fetchOne();
        if (row == null) return Optional.empty();
        return Optional.of(mapSession(row));
    }

    @Override
    public List<SearchSession> findAllActive() {
        return dsl.select(US_CHAT_ID, US_SESSION_STARTED_AT, US_LAST_HOURLY_SLOT)
            .from(USER_SETTINGS)
            .where(US_SESSION_STARTED_AT.isNotNull())
            .fetch(this::mapSession);
    }

    @Override
    public Optional<HourlySlotClaim> tryClaimHourlySlot(long chatId, Instant now) {
        return dsl.transactionResult(ctx -> {
            var tx = org.jooq.impl.DSL.using(ctx);
            Record row = tx.select(US_SESSION_STARTED_AT, US_LAST_HOURLY_SLOT)
                .from(USER_SETTINGS)
                .where(US_CHAT_ID.eq(chatId), US_SESSION_STARTED_AT.isNotNull())
                .forUpdate()
                .fetchOne();
            if (row == null) return Optional.empty();

            OffsetDateTime startOdt = row.get(US_SESSION_STARTED_AT);
            if (startOdt == null) return Optional.empty();

            Instant start = toInstant(startOdt);
            long elapsedSec = now.getEpochSecond() - start.getEpochSecond();
            int currentSlot = (int) (elapsedSec / 3600);
            if (currentSlot < 1) return Optional.empty();

            Integer lastSlot = row.get(US_LAST_HOURLY_SLOT);
            int last = lastSlot != null ? lastSlot : 0;
            if (currentSlot <= last) return Optional.empty();

            if (currentSlot > last + 1) {
                log.infof("hourly_report.skipped_stale_slots chatId=%d from=%d to=%d",
                    chatId, last + 1, currentSlot - 1);
            }

            tx.update(USER_SETTINGS)
                .set(US_LAST_HOURLY_SLOT, currentSlot)
                .set(US_UPDATED_AT, toOdt(now))
                .where(US_CHAT_ID.eq(chatId))
                .execute();

            return Optional.of(new HourlySlotClaim(currentSlot, lastSlot));
        });
    }

    @Override
    public void revertHourlySlot(long chatId, Integer previousSlot) {
        dsl.update(USER_SETTINGS)
            .set(US_LAST_HOURLY_SLOT, previousSlot)
            .set(US_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(US_CHAT_ID.eq(chatId))
            .execute();
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private SearchSession mapSession(Record r) {
        return new SearchSession(
            r.get(US_CHAT_ID),
            toInstant(r.get(US_SESSION_STARTED_AT)),
            r.get(US_LAST_HOURLY_SLOT)
        );
    }
}
