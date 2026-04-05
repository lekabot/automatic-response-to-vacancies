package ru.hhassistant.domain.model;

/**
 * Конечный автомат обработки вакансии в пайплайне.
 *
 * <pre>
 * (новая) ──► IN_PROGRESS ──► APPLIED
 *                           ──► ALREADY_APPLIED
 *                           ──► SKIPPED
 *                           ──► REQUIRES_TEST
 *                           ──► APPLY_PERM_ERROR   (терминальный)
 *                           ──► APPLY_TEMP_ERROR   (повтор через backoff)
 *                           ──► APPLY_TIMEOUT      (повтор через backoff)
 * </pre>
 */
public enum VacancyStatus {

    /** Процесс отклика начат, занята lease. */
    IN_PROGRESS,

    /** Отклик успешно отправлен. Терминальный. */
    APPLIED,

    /** Пользователь уже откликался на эту вакансию ранее. Терминальный. */
    ALREADY_APPLIED,

    /** Вакансия пропущена по exclude-ключевым словам. Терминальный. */
    SKIPPED,

    /** Вакансия требует выполнения теста — ручной обработки. Терминальный. */
    REQUIRES_TEST,

    /** Тайм-аут при отклике. Будет повтор через backoff. */
    APPLY_TIMEOUT,

    /** Временная ошибка hh.ru (5xx, captcha, rate-limit). Будет повтор через backoff. */
    APPLY_TEMP_ERROR,

    /** Постоянная ошибка (ошибка валидации, auth, неизвестная). Терминальный. */
    APPLY_PERM_ERROR;

    /**
     * Терминальные статусы: вакансия не будет обрабатываться повторно в рамках текущего
     * периода retention.
     */
    public boolean isTerminal() {
        return switch (this) {
            case APPLIED, ALREADY_APPLIED, SKIPPED, REQUIRES_TEST, APPLY_PERM_ERROR -> true;
            case IN_PROGRESS, APPLY_TIMEOUT, APPLY_TEMP_ERROR -> false;
        };
    }

    /** Статусы, которые допускают retry через backoff. */
    public boolean isRetryable() {
        return this == APPLY_TIMEOUT || this == APPLY_TEMP_ERROR;
    }
}
