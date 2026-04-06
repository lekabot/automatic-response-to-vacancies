package ru.hhassistant.infrastructure.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import ru.hhassistant.domain.model.SearchSession;
import ru.hhassistant.domain.port.SearchSessionRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ru.hhassistant.infrastructure.db.DbConverters.toInstant;
import static ru.hhassistant.infrastructure.db.DbConverters.toOdt;
import static ru.hhassistant.infrastructure.db.generated.Tables.USER_SETTINGS;

@ApplicationScoped
@Slf4j
public class SearchSessionRepositoryImpl implements SearchSessionRepository {

  @Inject DSLContext dsl;

  @Override
  public void start(long chatId, Instant startedAt) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    dsl.insertInto(USER_SETTINGS)
      .set(USER_SETTINGS.CHAT_ID, chatId)
      .set(USER_SETTINGS.SEARCH_SESSION_STARTED_AT, toOdt(startedAt))
      .set(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT, (Integer) null)
      .set(USER_SETTINGS.UPDATED_AT, now)
      .set(USER_SETTINGS.KEYWORDS_JSON, "[]")
      .onConflict(USER_SETTINGS.CHAT_ID)
      .doUpdate()
      .set(USER_SETTINGS.SEARCH_SESSION_STARTED_AT, toOdt(startedAt))
      .set(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT, (Integer) null)
      .set(USER_SETTINGS.UPDATED_AT, now)
      .execute();
  }

  @Override
  public boolean clear(long chatId) {
    int updated = dsl.update(USER_SETTINGS)
      .set(USER_SETTINGS.SEARCH_SESSION_STARTED_AT, (OffsetDateTime) null)
      .set(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT, (Integer) null)
      .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
      .where(USER_SETTINGS.CHAT_ID.eq(chatId),
        USER_SETTINGS.SEARCH_SESSION_STARTED_AT.isNotNull()
          .or(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT.isNotNull()))
      .execute();
    return updated > 0;
  }

  @Override
  public Optional<SearchSession> find(long chatId) {
    Record row = dsl.select(
        USER_SETTINGS.CHAT_ID,
        USER_SETTINGS.SEARCH_SESSION_STARTED_AT,
        USER_SETTINGS.LAST_HOURLY_REPORT_SLOT)
      .from(USER_SETTINGS)
      .where(USER_SETTINGS.CHAT_ID.eq(chatId), USER_SETTINGS.SEARCH_SESSION_STARTED_AT.isNotNull())
      .fetchOne();
    if (row == null) return Optional.empty();
    return Optional.of(mapSession(row));
  }

  @Override
  public List<SearchSession> findAllActive() {
    return dsl.select(
        USER_SETTINGS.CHAT_ID,
        USER_SETTINGS.SEARCH_SESSION_STARTED_AT,
        USER_SETTINGS.LAST_HOURLY_REPORT_SLOT)
      .from(USER_SETTINGS)
      .where(USER_SETTINGS.SEARCH_SESSION_STARTED_AT.isNotNull())
      .fetch(this::mapSession);
  }

  @Override
  public Optional<HourlySlotClaim> tryClaimHourlySlot(long chatId, Instant now) {
    return dsl.transactionResult(ctx -> {
      var tx = DSL.using(ctx);
      Record row = tx
        .select(USER_SETTINGS.SEARCH_SESSION_STARTED_AT, USER_SETTINGS.LAST_HOURLY_REPORT_SLOT)
        .from(USER_SETTINGS)
        .where(USER_SETTINGS.CHAT_ID.eq(chatId), USER_SETTINGS.SEARCH_SESSION_STARTED_AT.isNotNull())
        .forUpdate()
        .fetchOne();
      if (row == null) return Optional.empty();

      OffsetDateTime startOdt = row.get(USER_SETTINGS.SEARCH_SESSION_STARTED_AT);
      if (startOdt == null) return Optional.empty();

      Instant start = toInstant(startOdt);
      long elapsedSec = now.getEpochSecond() - start.getEpochSecond();
      int currentSlot = (int) (elapsedSec / 3600);
      if (currentSlot < 1) return Optional.empty();

      Integer lastSlot = row.get(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT);
      int last = lastSlot != null ? lastSlot : 0;
      if (currentSlot <= last) return Optional.empty();

      if (currentSlot > last + 1) {
        log.info("hourly_report.skipped_stale_slots chatId={} from={} to={}",
          chatId, last + 1, currentSlot - 1);
      }

      tx.update(USER_SETTINGS)
        .set(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT, currentSlot)
        .set(USER_SETTINGS.UPDATED_AT, toOdt(now))
        .where(USER_SETTINGS.CHAT_ID.eq(chatId))
        .execute();

      return Optional.of(new HourlySlotClaim(currentSlot, lastSlot));
    });
  }

  @Override
  public void revertHourlySlot(long chatId, Integer previousSlot) {
    dsl.update(USER_SETTINGS)
      .set(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT, previousSlot)
      .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
      .where(USER_SETTINGS.CHAT_ID.eq(chatId))
      .execute();
  }

  private SearchSession mapSession(Record r) {
    return new SearchSession(
      r.get(USER_SETTINGS.CHAT_ID),
      toInstant(r.get(USER_SETTINGS.SEARCH_SESSION_STARTED_AT)),
      r.get(USER_SETTINGS.LAST_HOURLY_REPORT_SLOT)
    );
  }
}
