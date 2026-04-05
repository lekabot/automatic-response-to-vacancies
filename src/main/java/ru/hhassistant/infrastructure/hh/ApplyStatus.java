package ru.hhassistant.infrastructure.hh;

/** Статус результата попытки отклика на уровне HTTP-клиента. */
public enum ApplyStatus {
    /** Отклик успешно отправлен. */
    APPLIED,
    /** Пользователь уже откликался на эту вакансию. */
    ALREADY_APPLIED,
    /** Тайм-аут HTTP-запроса. Можно повторить. */
    TIMEOUT,
    /** Временная ошибка: 5xx, captcha, rate-limit. Можно повторить. */
    TEMP_ERROR,
    /** Постоянная ошибка: невалидный запрос, запрещён. */
    PERM_ERROR,
    /** Ошибка аутентификации (401/403 или redirect на login). */
    AUTH_ERROR
}
