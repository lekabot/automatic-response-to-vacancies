package ru.hhassistant.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

/**
 * Типизированная конфигурация hh.ru, биндится к префиксу {@code hh}.
 * Все значения могут быть переопределены env-переменными:
 * {@code HH_USER_AGENT}, {@code HH_RATE_LIMIT_QPS}, etc.
 */
@ConfigMapping(prefix = "hh")
public interface HhConfig {

    @WithName("user-agent")
    @WithDefault("HHVacancyAssistant-Java/1.0")
    String userAgent();

    @WithName("rate-limit")
    RateLimitConfig rateLimit();

    SearchConfig search();

    interface RateLimitConfig {
        /** Запросов в секунду (token-bucket). */
        @WithDefault("2.0")
        double qps();

        /** Burst (пик, количество токенов). */
        @WithDefault("5")
        int burst();
    }

    interface SearchConfig {
        /**
         * Глобальные стоп-слова, применяются ко всем пользователям.
         * Пустое значение = нет стоп-слов. В properties задаётся как comma-separated list.
         * Не используй @WithDefault("") — SmallRye Config не парсит пустую строку в пустой List.
         */
        @WithName("exclude-keywords")
        Optional<List<String>> excludeKeywords();

        /** Регионы: 1=Москва, 2=СПб, 113=Россия. */
        @WithName("area")
        @WithDefault("1,2")
        List<Integer> area();

        /** Графики работы: remote, fullDay, shift, flexible. */
        @WithName("schedule")
        @WithDefault("remote,fullDay")
        List<String> schedule();

        /** Типы занятости: full, part, project, volunteer. */
        @WithName("employment")
        @WithDefault("full")
        List<String> employment();

        /** Поле поиска (null = по умолчанию hh.ru). */
        @WithName("search-field")
        Optional<String> searchField();

        /** Публикация вакансий не старше N часов. */
        @WithName("published-within-hours")
        @WithDefault("24")
        int publishedWithinHours();

        /** Максимум вакансий за один polling-цикл. */
        @WithName("max-vacancies-per-run")
        @WithDefault("200")
        int maxVacanciesPerRun();

        /** Дневной лимит откликов. hh.ru ограничивает ~200/день. */
        @WithName("daily-apply-limit")
        @WithDefault("200")
        int dailyApplyLimit();

        /** Базовый интервал между polling-циклами (секунды). */
        @WithName("poll-interval-seconds")
        @WithDefault("60")
        double pollIntervalSeconds();

        /** Максимальный интервал при backoff (секунды). */
        @WithName("poll-interval-max-seconds")
        @WithDefault("300")
        double pollIntervalMaxSeconds();

        /** Включить ли backoff при повторяющихся пустых результатах. */
        @WithName("same-result-backoff-enabled")
        @WithDefault("true")
        boolean sameResultBackoffEnabled();

        /** Минут: lease-время IN_PROGRESS до автоматического освобождения. */
        @WithName("vacancy-lease-minutes")
        @WithDefault("10")
        int vacancyLeaseMinutes();

        /** Количество вакансий между heartbeat-логами. */
        @WithName("heartbeat-every")
        @WithDefault("10")
        int heartbeatEvery();

        /** Общий таймаут на весь цикл apply одной вакансии (секунды). */
        @WithName("apply-total-timeout-seconds")
        @WithDefault("120")
        int applyTotalTimeoutSeconds();

        /** Таймаут на одну HTTP-попытку apply (секунды). */
        @WithName("apply-per-attempt-timeout-seconds")
        @WithDefault("35")
        int applyPerAttemptTimeoutSeconds();
    }
}
