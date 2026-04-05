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
 * Отправляет почасовой отчёт о прогрессе сессии.
 *
 * <p>Отчёт отправляется только один раз за слот (slot = часы с момента старта сессии).
 * При неудачной отправке слот откатывается через {@link SearchSessionRepository#revertHourlySlot}.
 */
@ApplicationScoped
public class HourlyReportService {

    private static final Logger log = Logger.getLogger(HourlyReportService.class);

    @Inject VacancyRepository vacancyRepository;
    @Inject SearchSessionRepository sessionRepository;
    @Inject NotificationPort notificationPort;
    @Inject Clock clock;

    /**
     * Проверяет, нужно ли отправить hourly-отчёт, и при необходимости отправляет.
     * Безопасно вызывать часто — внутри атомарная проверка слота.
     *
     * @param session текущая сессия пользователя
     * @param dailyLimit дневной лимит откликов для статистики
     */
    public void maybeFireHourlyReport(SearchSession session, int dailyLimit) {
        Instant now = clock.instant();
        if (!session.isHourlyReportDue(now)) return;

        var claim = sessionRepository.tryClaimHourlySlot(session.chatId(), now);
        if (claim.isEmpty()) return;

        var slot = claim.get();
        log.infof("hourly_report.due chatId=%d slot=%d", session.chatId(), slot.claimedSlot());

        try {
            ReportSnapshot snapshot = buildSnapshot(session, now, dailyLimit);
            notificationPort.sendHourlyReport(session.chatId(), snapshot);
            log.infof("hourly_report.sent chatId=%d slot=%d", session.chatId(), slot.claimedSlot());
        } catch (Exception ex) {
            log.errorf(ex, "hourly_report.send_failed chatId=%d slot=%d",
                session.chatId(), slot.claimedSlot());
            sessionRepository.revertHourlySlot(session.chatId(), slot.previousSlot());
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
