package ru.hhassistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.model.SearchSession;
import ru.hhassistant.domain.port.SearchSessionRepository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Шедулер polling-циклов.
 *
 * <p>Ключевые свойства:
 * <ul>
 *   <li>Запускается по Quarkus Scheduler (cron/every expression из конфига).</li>
 *   <li>Итерирует все активные сессии из БД.</li>
 *   <li>Каждая сессия обрабатывается на virtual thread (блокирующий HTTP I/O).</li>
 *   <li>Semaphore на chatId предотвращает параллельную обработку одного пользователя.</li>
 * </ul>
 *
 * <p>Telegram не участвует в этом классе.
 */
@ApplicationScoped
@Slf4j
public class SearchSessionScheduler {

    @Inject SearchSessionService sessionService;
    @Inject SearchSessionRepository sessionRepository;
    @Inject HhConfig hhConfig;
    @Inject MeterRegistry meterRegistry;

    /** По одному слоту на пользователя — не обрабатывать параллельно. */
    private final ConcurrentHashMap<Long, Semaphore> perUserLocks = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event) {
        log.info("SearchSessionScheduler started");
    }

    /**
     * Основной polling-триггер. Интервал задаётся через {@code hh.search.poll-interval-seconds}.
     * Quarkus Scheduler поддерживает duration-выражения типа {@code {value}S}.
     */
    @Scheduled(every = "${hh.search.poll-interval-seconds:60}S",
               identity = "hh-polling-cycle")
    void scheduledPoll(ScheduledExecution execution) {
        log.debug("scheduler.tick scheduledAt={}", execution.getScheduledFireTime());
        List<SearchSession> activeSessions = sessionRepository.findAllActive();
        if (activeSessions.isEmpty()) return;

        log.info("scheduler.active_sessions count={}", activeSessions.size());
        meterRegistry.gauge("hh.scheduler.active_sessions", activeSessions.size());

        for (SearchSession session : activeSessions) {
            Thread.ofVirtual()
                .name("poll-" + session.chatId())
                .start(() -> runCycleGuarded(session));
        }
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private void runCycleGuarded(SearchSession session) {
        long chatId = session.chatId();
        Semaphore sem = perUserLocks.computeIfAbsent(chatId, k -> new Semaphore(1));
        if (!sem.tryAcquire()) {
            log.debug("scheduler.skip_concurrent chatId={}", chatId);
            return;
        }
        try {
            SearchSessionService.CycleOutcome outcome = sessionService.executeCycle(session);
            meterRegistry.counter("hh.polling_cycle.outcome",
                "result", outcome.name()).increment();

            if (outcome == SearchSessionService.CycleOutcome.SESSION_INVALID
                || outcome == SearchSessionService.CycleOutcome.DAILY_LIMIT_REACHED) {
                perUserLocks.remove(chatId);
            }
        } catch (Exception ex) {
            log.error("scheduler.cycle_crashed chatId={}", chatId, ex);
            meterRegistry.counter("hh.polling_cycle.crashed").increment();
        } finally {
            sem.release();
        }
    }
}
