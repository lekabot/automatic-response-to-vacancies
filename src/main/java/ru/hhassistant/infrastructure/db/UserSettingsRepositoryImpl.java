package ru.hhassistant.infrastructure.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import ru.hhassistant.domain.port.UserSettingsRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ru.hhassistant.infrastructure.db.generated.Tables.USER_SETTINGS;

@ApplicationScoped
@Slf4j
public class UserSettingsRepositoryImpl implements UserSettingsRepository {
  @Inject
  DSLContext dsl;
  @Inject
  ObjectMapper objectMapper;

  private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {
  };

  @Override
  public Optional<UserSettingsRow> findByChatId(long chatId) {
    Record row = dsl.select().from(USER_SETTINGS)
      .where(USER_SETTINGS.CHAT_ID.eq(chatId))
      .fetchOne();
    if (row == null) return Optional.empty();
    return Optional.of(mapRow(row));
  }

  @Override
  public void save(UserSettingsRow row) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String keywordsJson = Unchecked.supplier(() -> objectMapper.writeValueAsString(row.keywords() != null ? row.keywords() : List.of())).get();
    dsl.insertInto(USER_SETTINGS)
      .set(USER_SETTINGS.CHAT_ID, row.chatId())
      .set(USER_SETTINGS.KEYWORDS_JSON, keywordsJson)
      .set(USER_SETTINGS.COVER_LETTER, row.coverLetter())
      .set(USER_SETTINGS.HH_EMAIL, row.email())
      .set(USER_SETTINGS.HHTOKEN, row.hhtoken())
      .set(USER_SETTINGS.RESUME_ID, row.resumeId())
      .set(USER_SETTINGS.RESUME_TITLE, row.resumeTitle())
      .set(USER_SETTINGS.UPDATED_AT, now)
      .onConflict(USER_SETTINGS.CHAT_ID)
      .doUpdate()
      .set(USER_SETTINGS.KEYWORDS_JSON, keywordsJson)
      .set(USER_SETTINGS.COVER_LETTER, row.coverLetter())
      .set(USER_SETTINGS.HH_EMAIL, row.email())
      .set(USER_SETTINGS.HHTOKEN, row.hhtoken())
      .set(USER_SETTINGS.RESUME_ID, row.resumeId())
      .set(USER_SETTINGS.RESUME_TITLE, row.resumeTitle())
      .set(USER_SETTINGS.UPDATED_AT, now)
      .execute();
  }

  @Override
  public void updateKeywords(long chatId, List<String> keywords) {
    dsl.update(USER_SETTINGS)
      .set(USER_SETTINGS.KEYWORDS_JSON, Unchecked.supplier(() -> objectMapper.writeValueAsString(keywords != null ? keywords : List.of())).get())
      .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
      .where(USER_SETTINGS.CHAT_ID.eq(chatId))
      .execute();
  }

  @Override
  public void updateCoverLetter(long chatId, String coverLetter) {
    dsl.update(USER_SETTINGS)
      .set(USER_SETTINGS.COVER_LETTER, coverLetter)
      .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
      .where(USER_SETTINGS.CHAT_ID.eq(chatId))
      .execute();
  }

  @Override
  public void updateAuth(long chatId, String email, String hhtoken) {
    dsl.update(USER_SETTINGS)
      .set(USER_SETTINGS.HH_EMAIL, email)
      .set(USER_SETTINGS.HHTOKEN, hhtoken)
      .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
      .where(USER_SETTINGS.CHAT_ID.eq(chatId))
      .execute();
  }

  @Override
  public void updateResume(long chatId, String resumeId, String resumeTitle) {
    dsl.update(USER_SETTINGS)
      .set(USER_SETTINGS.RESUME_ID, resumeId)
      .set(USER_SETTINGS.RESUME_TITLE, resumeTitle)
      .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
      .where(USER_SETTINGS.CHAT_ID.eq(chatId))
      .execute();
  }

  private UserSettingsRow mapRow(Record r) {
    List<String> keywords = Unchecked.supplier(() -> objectMapper.readValue(r.get(USER_SETTINGS.KEYWORDS_JSON, String.class), LIST_OF_STRING)).get();
    return new UserSettingsRow(
      r.get(USER_SETTINGS.CHAT_ID),
      r.get(USER_SETTINGS.HH_EMAIL),
      r.get(USER_SETTINGS.HHTOKEN),
      keywords,
      r.get(USER_SETTINGS.COVER_LETTER),
      r.get(USER_SETTINGS.RESUME_ID),
      r.get(USER_SETTINGS.RESUME_TITLE)
    );
  }
}
