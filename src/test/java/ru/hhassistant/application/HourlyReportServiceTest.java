package ru.hhassistant.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.domain.model.ReportSnapshot;
import ru.hhassistant.domain.model.SearchSession;
import ru.hhassistant.domain.port.NotificationPort;
import ru.hhassistant.domain.port.SearchSessionRepository;
import ru.hhassistant.domain.port.VacancyRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HourlyReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T13:00:00Z");
    private static final Instant SESSION_START = Instant.parse("2026-04-01T10:00:00Z");
    private static final long CHAT_ID = 1L;
    private static final int DAILY_LIMIT = 50;

    @Mock VacancyRepository vacancyRepository;
    @Mock SearchSessionRepository sessionRepository;
    @Mock NotificationPort notificationPort;

    private HourlyReportService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new HourlyReportService();
        inject(service, "vacancyRepository", vacancyRepository);
        inject(service, "sessionRepository", sessionRepository);
        inject(service, "notificationPort", notificationPort);
        inject(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void maybeFireHourlyReport_whenNotDue_doesNothing() {
        // NOW is 3h after start → slot 3 is due, but lastReportedSlot=3 means not due
        SearchSession session = new SearchSession(CHAT_ID, SESSION_START, 3);
        service.maybeFireHourlyReport(session, DAILY_LIMIT);

        verifyNoInteractions(sessionRepository, notificationPort);
    }

    @Test
    void maybeFireHourlyReport_whenDue_butSlotAlreadyClaimed_doesNothing() {
        // Slot 3 is due and session hasn't reported it yet
        SearchSession session = new SearchSession(CHAT_ID, SESSION_START, 2);
        when(sessionRepository.tryClaimHourlySlot(anyLong(), any())).thenReturn(Optional.empty());

        service.maybeFireHourlyReport(session, DAILY_LIMIT);

        verify(sessionRepository).tryClaimHourlySlot(CHAT_ID, NOW);
        verifyNoInteractions(notificationPort);
    }

    @Test
    void maybeFireHourlyReport_whenDue_andSlotClaimed_sendsReport() {
        SearchSession session = new SearchSession(CHAT_ID, SESSION_START, 2);
        var slotClaim = new SearchSessionRepository.HourlySlotClaim(3, 2);
        ReportSnapshot snapshot = emptySnapshot();

        when(sessionRepository.tryClaimHourlySlot(anyLong(), any())).thenReturn(Optional.of(slotClaim));
        when(vacancyRepository.requiresTestInWindow(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(vacancyRepository.sessionStats(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);

        service.maybeFireHourlyReport(session, DAILY_LIMIT);

        verify(notificationPort).sendHourlyReport(eq(CHAT_ID), any());
    }

    @Test
    void maybeFireHourlyReport_whenSendFails_revertsSlot() {
        SearchSession session = new SearchSession(CHAT_ID, SESSION_START, 2);
        var slotClaim = new SearchSessionRepository.HourlySlotClaim(3, 2);
        ReportSnapshot snapshot = emptySnapshot();

        when(sessionRepository.tryClaimHourlySlot(anyLong(), any())).thenReturn(Optional.of(slotClaim));
        when(vacancyRepository.requiresTestInWindow(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(vacancyRepository.sessionStats(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);
        doThrow(new RuntimeException("Telegram error")).when(notificationPort).sendHourlyReport(anyLong(), any());

        service.maybeFireHourlyReport(session, DAILY_LIMIT);

        verify(sessionRepository).revertHourlySlot(eq(CHAT_ID), eq(2));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static ReportSnapshot emptySnapshot() {
        return new ReportSnapshot(CHAT_ID, SESSION_START, NOW, 0, 0, 0, 0, 0, 0, 0, 0, DAILY_LIMIT, List.of());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
