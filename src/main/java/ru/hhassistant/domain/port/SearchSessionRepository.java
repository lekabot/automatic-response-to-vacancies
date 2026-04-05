package ru.hhassistant.domain.port;

import ru.hhassistant.domain.model.SearchSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Порт управления поисковыми сессиями.
 */
public interface SearchSessionRepository {

    /** Создаёт или заменяет запись поисковой сессии. */
    void start(long chatId, Instant startedAt);

    /** Сбрасывает сессию. Идемпотентно. Возвращает true если сессия была активной. */
    boolean clear(long chatId);

    Optional<SearchSession> find(long chatId);

    /** Все активные сессии — для scheduler'а, который проходит по всем пользователям. */
    List<SearchSession> findAllActive();

    /**
     * Атомарно занимает текущий hourly-слот.
     * Возвращает {@code Optional.empty()} если слот уже занят или прошло меньше часа.
     * В случае успеха возвращает пару (claimedSlot, previousSlot).
     */
    Optional<HourlySlotClaim> tryClaimHourlySlot(long chatId, Instant now);

    /** Откат hourly-слота после неудачной отправки. */
    void revertHourlySlot(long chatId, Integer previousSlot);

    record HourlySlotClaim(int claimedSlot, Integer previousSlot) {}
}
