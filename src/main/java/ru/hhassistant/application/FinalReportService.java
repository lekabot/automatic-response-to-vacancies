package ru.hhassistant.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
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
public class FinalReportService {

    private static final Logger log = Logger.getLogger(FinalReportService.class);

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
        log.infof("final_report.sending chatId=%d", session.chatId());

        try {
            ReportSnapshot snapshot = buildSnapshot(session, now, dailyLimit);
            notificationPort.sendFinalReport(session.chatId(), snapshot);
            log.infof("final_report.sent chatId=%d applied=%d",
                session.chatId(), snapshot.applied());
        } catch (Exception ex) {
            log.errorf(ex, "final_report.send_failed chatId=%d", session.chatId());
        } finally {
            boolean cleared = sessionRepository.clear(session.chatId());
            if (cleared) {
                log.infof("search_session.cleared chatId=%d reason=final_report", session.chatId());
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
