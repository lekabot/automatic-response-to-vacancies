package ru.hhassistant.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "hh")
public interface HhConfig {
  @WithDefault("HHVacancyAssistant-Java/1.0")
  String userAgent();

  RateLimitConfig rateLimit();

  SearchConfig search();

  interface RateLimitConfig {
    /**
     * Запросов в секунду (token-bucket).
     */
    @WithDefault("2.0")
    double qps();

    /**
     * Burst (пик, количество токенов).
     */
    @WithDefault("5")
    int burst();
  }

  interface SearchConfig {
    /**
     * Глобальные стоп-слова, применяются ко всем пользователям.
     * Пустое значение = нет стоп-слов. В properties задаётся как comma-separated list.
     * Не используй @WithDefault("") — SmallRye Config не парсит пустую строку в пустой List.
     */
    Optional<List<String>> excludeKeywords();

    /**
     * Регионы: 1=Москва, 2=СПб, 113=Россия.
     */
    @WithDefault("113")
    List<Integer> area();

    /**
     * Графики работы: remote, fullDay, shift, flexible.
     */
    @WithDefault("remote,fullDay,shift,flexible")
    List<String> schedule();

    /**
     * Типы занятости: full, part, project, volunteer.
     */
    @WithDefault("full,part,project,volunteer")
    List<String> employment();

    /**
     * Поле поиска (null = по умолчанию hh.ru).
     */
    Optional<String> searchField();

    /**
     * Публикация вакансий не старше N часов.
     */
    @WithDefault("24")
    int publishedWithinHours();

    /**
     * Максимум вакансий за один polling-цикл.
     */
    @WithDefault("200")
    int maxVacanciesPerRun();

    /**
     * Дневной лимит откликов. hh.ru ограничивает ~200/день.
     */
    @WithDefault("200")
    int dailyApplyLimit();

    /**
     * Базовый интервал между polling-циклами (секунды).
     */
    @WithDefault("10")
    double pollIntervalSeconds();

    /**
     * Максимальный интервал при backoff (секунды).
     */
    @WithDefault("300")
    double pollIntervalMaxSeconds();

    /**
     * Включить ли backoff при повторяющихся пустых результатах.
     */
    @WithDefault("true")
    boolean sameResultBackoffEnabled();

    /**
     * Минут: lease-время IN_PROGRESS до автоматического освобождения.
     */
    @WithDefault("1")
    int vacancyLeaseMinutes();

    /**
     * Количество вакансий между heartbeat-логами.
     */
    @WithDefault("10")
    int heartbeatEvery();

    /**
     * Общий таймаут на весь цикл apply одной вакансии (секунды).
     */
    @WithDefault("120")
    int applyTotalTimeoutSeconds();

    /**
     * Таймаут на одну HTTP-попытку apply (секунды).
     */
    @WithDefault("35")
    int applyPerAttemptTimeoutSeconds();
  }
}
