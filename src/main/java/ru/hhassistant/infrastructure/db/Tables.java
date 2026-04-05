package ru.hhassistant.infrastructure.db;

import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.time.OffsetDateTime;

/**
 * Константы таблиц и полей для jOOQ DSL.
 *
 * <p>В продакшн-проекте этот класс должен быть заменён кодогенерированными классами
 * (./gradlew generateJooq). До момента настройки генератора используется ручное
 * определение на основе V1__initial_schema.sql.
 */
public final class Tables {

    private Tables() {}

    // ─── vacancies_seen ───────────────────────────────────────────────────────

    public static final Table<?> VACANCIES_SEEN = DSL.table("vacancies_seen");

    public static final Field<Long>           VS_CHAT_ID              = DSL.field("chat_id", Long.class);
    public static final Field<String>         VS_VACANCY_ID           = DSL.field("vacancy_id", String.class);
    public static final Field<String>         VS_TITLE                = DSL.field("title", String.class);
    public static final Field<String>         VS_EMPLOYER             = DSL.field("employer", String.class);
    public static final Field<String>         VS_URL                  = DSL.field("url", String.class);
    public static final Field<String>         VS_SALARY_TEXT          = DSL.field("salary_text", String.class);
    public static final Field<String>         VS_STATUS               = DSL.field("status", String.class);
    public static final Field<Integer>        VS_ATTEMPT_COUNT        = DSL.field("attempt_count", Integer.class);
    public static final Field<String>         VS_LAST_ERROR           = DSL.field("last_error", String.class);
    public static final Field<OffsetDateTime> VS_LAST_ATTEMPT_AT      = DSL.field("last_attempt_at", OffsetDateTime.class);
    public static final Field<OffsetDateTime> VS_NEXT_RETRY_AT        = DSL.field("next_retry_at", OffsetDateTime.class);
    public static final Field<OffsetDateTime> VS_PROCESSING_STARTED_AT= DSL.field("processing_started_at", OffsetDateTime.class);
    public static final Field<OffsetDateTime> VS_SEEN_AT              = DSL.field("seen_at", OffsetDateTime.class);

    // ─── user_settings ────────────────────────────────────────────────────────

    public static final Table<?> USER_SETTINGS = DSL.table("user_settings");

    public static final Field<Long>           US_CHAT_ID              = DSL.field("chat_id", Long.class);
    public static final Field<String>         US_KEYWORDS_JSON        = DSL.field("keywords_json", String.class);
    public static final Field<String>         US_COVER_LETTER         = DSL.field("cover_letter", String.class);
    public static final Field<String>         US_EMAIL                = DSL.field("hh_email", String.class);
    public static final Field<String>         US_HHTOKEN              = DSL.field("hhtoken", String.class);
    public static final Field<String>         US_RESUME_ID            = DSL.field("resume_id", String.class);
    public static final Field<String>         US_RESUME_TITLE         = DSL.field("resume_title", String.class);
    public static final Field<OffsetDateTime> US_UPDATED_AT           = DSL.field("updated_at", OffsetDateTime.class);
    public static final Field<OffsetDateTime> US_SESSION_STARTED_AT   = DSL.field("search_session_started_at", OffsetDateTime.class);
    public static final Field<Integer>        US_LAST_HOURLY_SLOT     = DSL.field("last_hourly_report_slot", Integer.class);
}
