package ru.hhassistant.infrastructure.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jboss.logging.Logger;
import ru.hhassistant.domain.port.UserSettingsRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ru.hhassistant.infrastructure.db.Tables.*;

/**
 * jOOQ-реализация {@link UserSettingsRepository}.
 */
@ApplicationScoped
public class UserSettingsRepositoryImpl implements UserSettingsRepository {

    private static final Logger log = Logger.getLogger(UserSettingsRepositoryImpl.class);

    @Inject DSLContext dsl;
    @Inject ObjectMapper objectMapper;

    @Override
    public Optional<UserSettingsRow> findByChatId(long chatId) {
        Record row = dsl.select().from(USER_SETTINGS)
            .where(US_CHAT_ID.eq(chatId))
            .fetchOne();
        if (row == null) return Optional.empty();
        return Optional.of(mapRow(row));
    }

    @Override
    public void save(UserSettingsRow row) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String keywordsJson = toJson(row.keywords());
        dsl.insertInto(USER_SETTINGS)
            .set(US_CHAT_ID, row.chatId())
            .set(US_KEYWORDS_JSON, keywordsJson)
            .set(US_COVER_LETTER, row.coverLetter())
            .set(US_EMAIL, row.email())
            .set(US_HHTOKEN, row.hhtoken())
            .set(US_RESUME_ID, row.resumeId())
            .set(US_RESUME_TITLE, row.resumeTitle())
            .set(US_UPDATED_AT, now)
            .onConflict(US_CHAT_ID)
            .doUpdate()
            .set(US_KEYWORDS_JSON, keywordsJson)
            .set(US_COVER_LETTER, row.coverLetter())
            .set(US_EMAIL, row.email())
            .set(US_HHTOKEN, row.hhtoken())
            .set(US_RESUME_ID, row.resumeId())
            .set(US_RESUME_TITLE, row.resumeTitle())
            .set(US_UPDATED_AT, now)
            .execute();
    }

    @Override
    public void updateKeywords(long chatId, List<String> keywords) {
        dsl.update(USER_SETTINGS)
            .set(US_KEYWORDS_JSON, toJson(keywords))
            .set(US_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(US_CHAT_ID.eq(chatId))
            .execute();
    }

    @Override
    public void updateCoverLetter(long chatId, String coverLetter) {
        dsl.update(USER_SETTINGS)
            .set(US_COVER_LETTER, coverLetter)
            .set(US_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(US_CHAT_ID.eq(chatId))
            .execute();
    }

    @Override
    public void updateAuth(long chatId, String email, String hhtoken) {
        dsl.update(USER_SETTINGS)
            .set(US_EMAIL, email)
            .set(US_HHTOKEN, hhtoken)
            .set(US_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(US_CHAT_ID.eq(chatId))
            .execute();
    }

    @Override
    public void updateResume(long chatId, String resumeId, String resumeTitle) {
        dsl.update(USER_SETTINGS)
            .set(US_RESUME_ID, resumeId)
            .set(US_RESUME_TITLE, resumeTitle)
            .set(US_UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(US_CHAT_ID.eq(chatId))
            .execute();
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private UserSettingsRow mapRow(Record r) {
        List<String> keywords = fromJson(r.get(US_KEYWORDS_JSON));
        return new UserSettingsRow(
            r.get(US_CHAT_ID),
            r.get(US_EMAIL),
            r.get(US_HHTOKEN),
            keywords,
            r.get(US_COVER_LETTER),
            r.get(US_RESUME_ID),
            r.get(US_RESUME_TITLE)
        );
    }

    private String toJson(List<String> keywords) {
        try {
            return objectMapper.writeValueAsString(keywords != null ? keywords : List.of());
        } catch (Exception ex) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception ex) {
            return List.of();
        }
    }
}
