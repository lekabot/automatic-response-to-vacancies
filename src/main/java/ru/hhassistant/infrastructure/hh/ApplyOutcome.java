package ru.hhassistant.infrastructure.hh;

/**
 * Результат одной попытки отклика на вакансию.
 *
 * @param status      классифицированный статус
 * @param httpStatus  HTTP-статус ответа, null при transport error
 * @param errorCode   краткий код ошибки для логов и метрик
 * @param retryable   можно ли повторить этот конкретный запрос
 */
public record ApplyOutcome(
    ApplyStatus status,
    Integer httpStatus,
    String errorCode,
    boolean retryable
) {

    public static ApplyOutcome applied() {
        return new ApplyOutcome(ApplyStatus.APPLIED, 200, null, false);
    }

    public static ApplyOutcome alreadyApplied() {
        return new ApplyOutcome(ApplyStatus.ALREADY_APPLIED, 200, "alreadyApplied", false);
    }

    public static ApplyOutcome tempError(String errorCode, String detail) {
        return new ApplyOutcome(ApplyStatus.TEMP_ERROR, null, errorCode, true);
    }

    public static ApplyOutcome permError(String errorCode) {
        return new ApplyOutcome(ApplyStatus.PERM_ERROR, null, errorCode, false);
    }

    public static ApplyOutcome authError(String errorCode) {
        return new ApplyOutcome(ApplyStatus.AUTH_ERROR, null, errorCode, false);
    }

    public static ApplyOutcome timeout() {
        return new ApplyOutcome(ApplyStatus.TIMEOUT, null, "timeout", true);
    }
}
