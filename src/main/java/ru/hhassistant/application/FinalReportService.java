package ru.hhassistant.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.domain.model.ReportSnapshot;
import ru.hhassistant.domain.model.SearchSession;
import ru.hhassistant.domain.port.NotificationPort;
import ru.hhassistant.domain.port.SearchSessionRepository;
import ru.hhassistant.domain.port.VacancyRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Отправляет итоговый отчёт при завершении/остановке сессии.
 */
@ApplicationScoped
@Slf4j
public class FinalReportService {

    @Inject VacancyRepository vacancyRepository;
    @Inject SearchSessionRepository sessionRepository;
    @Inject NotificationPort notificationPort;
    @Inject Clock clock;

    /**
     * Отправляет итоговый отчёт и очищает сессию.
     * Идемпотентен: если сессия уже очищена — только отчёт без double-clear.
     */
    public void sendFinalAndClearSession(SearchSession session, int dailyLimit) {
        Instant now = clock.instant();
        log.info("final_report.sending chatId={}", session.chatId());

        try {
            ReportSnapshot snapshot = buildSnapshot(session, now, dailyLimit);
            notificationPort.sendFinalReport(session.chatId(), snapshot);
            log.info("final_report.sent chatId={} applied={}",
                session.chatId(), snapshot.applied());
        } catch (Exception ex) {
            log.error("final_report.send_failed chatId={}", session.chatId(), ex);
        } finally {
            boolean cleared = sessionRepository.clear(session.chatId());
            if (cleared) {
                log.info("search_session.cleared chatId={} reason=final_report", session.chatId());
            }
        }
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private ReportSnapshot buildSnapshot(SearchSession session, Instant now, int dailyLimit) {
        Instant windowStart = session.startedAt();
        List<ReportSnapshot.TestVacancyRef> testVacancies =
            vacancyRepository.requiresTestInWindow(session.chatId(), windowStart, 20);
        return vacancyRepository.sessionStats(session.chatId(), windowStart, now, dailyLimit)
            .withRequiresTestVacancies(testVacancies);
    }
}
