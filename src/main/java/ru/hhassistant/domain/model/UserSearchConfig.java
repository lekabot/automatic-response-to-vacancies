package ru.hhassistant.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * Настройки поиска конкретного пользователя, прочитанные из БД и смёрдженные с глобальным config.
 * Иммутабельный snapshot; перечитывается перед каждым polling-циклом.
 */
public record UserSearchConfig(
    long chatId,
    String resumeId,
    String resumeTitle,
    List<String> keywords,
    String coverLetterTemplate,   // null = без письма
    String hhtoken,               // null если не авторизован
    List<String> excludeKeywords,
    List<Integer> searchAreas,
    List<String> schedules,
    List<String> employmentTypes,
    Optional<String> searchField, // empty = по умолчанию
    int dailyApplyLimit,
    int publishedWithinHours,
    int maxVacanciesPerRun,
    int retentionDays,
    int leaseMinutes,
    double pollIntervalSeconds,
    double pollIntervalMaxSeconds,
    boolean sameResultBackoffEnabled,
    int applyTotalTimeoutSeconds,
    int applyPerAttemptTimeoutSeconds
) {
    /** Пользователь полностью настроен и готов к запуску поиска. */
    public boolean isComplete() {
        return resumeId != null && !resumeId.isBlank()
            && !keywords.isEmpty();
    }

    /** Количество дней публикации вакансий, переведённых из часов. */
    public int publishedWithinDays() {
        return Math.max(1, publishedWithinHours / 24);
    }
}
