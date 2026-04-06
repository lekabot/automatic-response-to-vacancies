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

@Slf4j
@ApplicationScoped
public class HourlyReportService {
  @Inject
  VacancyRepository vacancyRepository;
  @Inject
  SearchSessionRepository sessionRepository;
  @Inject
  NotificationPort notificationPort;
  @Inject
  Clock clock;

  public void maybeFireHourlyReport(SearchSession session, int dailyLimit) {
    var now = clock.instant();
    if (!session.isHourlyReportDue(now)) return;

    var claim = sessionRepository.tryClaimHourlySlot(session.chatId(), now);
    if (claim.isEmpty()) return;

    var slot = claim.get();
    log.info("hourly_report.due chatId={} slot={}", session.chatId(), slot.claimedSlot());

    try {
      ReportSnapshot snapshot = buildSnapshot(session, now, dailyLimit);
      notificationPort.sendHourlyReport(session.chatId(), snapshot);
      log.info("hourly_report.sent chatId={} slot={}", session.chatId(), slot.claimedSlot());
    } catch (Exception ex) {
      log.error("hourly_report.send_failed chatId={} slot={}",
        session.chatId(), slot.claimedSlot(), ex);
      sessionRepository.revertHourlySlot(session.chatId(), slot.previousSlot());
    }
  }

  private ReportSnapshot buildSnapshot(SearchSession session, Instant now, int dailyLimit) {
    Instant windowStart = session.startedAt();
    List<ReportSnapshot.TestVacancyRef> testVacancies =
      vacancyRepository.requiresTestInWindow(session.chatId(), windowStart, 20);
    return vacancyRepository.sessionStats(session.chatId(), windowStart, now, dailyLimit)
      .withRequiresTestVacancies(testVacancies);
  }
}
