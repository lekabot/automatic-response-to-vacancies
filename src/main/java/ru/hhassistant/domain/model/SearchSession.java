package ru.hhassistant.domain.model;

import java.time.Instant;

/**
 * Активная поисковая сессия пользователя.
 * Сессия создаётся при запуске поиска и сбрасывается при остановке / достижении лимита.
 */
public record SearchSession(
    long chatId,
    Instant startedAt,
    Integer lastHourlyReportSlot  // null если ещё не было hourly-отчёта
) {

    /**
     * Возвращает текущий слот почасового отчёта.
     * slot = floor((now - startedAt) / 3600).
     * Первый слот = 1 (т.е. первый отчёт через 1 час после старта).
     */
    public int currentHourlySlot(Instant now) {
        long elapsed = now.getEpochSecond() - startedAt.getEpochSecond();
        return (int) (elapsed / 3600);
    }

    /**
     * Нужно ли отправить hourly-отчёт прямо сейчас.
     * Отправляется только если currentSlot >= 1 и ещё не отправлен для этого слота.
     */
    public boolean isHourlyReportDue(Instant now) {
        int current = currentHourlySlot(now);
        if (current < 1) return false;
        return lastHourlyReportSlot == null || current > lastHourlyReportSlot;
    }
}
