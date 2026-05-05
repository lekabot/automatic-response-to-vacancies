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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinalReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T20:00:00Z");
    private static final Instant SESSION_START = Instant.parse("2026-04-01T10:00:00Z");
    private static final long CHAT_ID = 42L;
    private static final int DAILY_LIMIT = 50;

    @Mock VacancyRepository vacancyRepository;
    @Mock SearchSessionRepository sessionRepository;
    @Mock NotificationPort notificationPort;

    private FinalReportService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FinalReportService();
        inject(service, "vacancyRepository", vacancyRepository);
        inject(service, "sessionRepository", sessionRepository);
        inject(service, "notificationPort", notificationPort);
        inject(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sendFinalAndClearSession_happyPath_sendsReportAndClearsSession() {
        ReportSnapshot snapshot = emptySnapshot(5);
        when(vacancyRepository.requiresTestInWindow(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(vacancyRepository.sessionStats(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);
        when(sessionRepository.clear(anyLong())).thenReturn(true);

        service.sendFinalAndClearSession(session(), DAILY_LIMIT);

        verify(notificationPort).sendFinalReport(eq(CHAT_ID), any());
        verify(sessionRepository).clear(CHAT_ID);
    }

    @Test
    void sendFinalAndClearSession_whenSendFails_stillClearsSession() {
        // MN-3: сессия должна очищаться даже при ошибке отправки (finally block)
        ReportSnapshot snapshot = emptySnapshot(3);
        when(vacancyRepository.requiresTestInWindow(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(vacancyRepository.sessionStats(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);
        doThrow(new RuntimeException("Telegram unavailable")).when(notificationPort).sendFinalReport(anyLong(), any());
        when(sessionRepository.clear(anyLong())).thenReturn(true);

        service.sendFinalAndClearSession(session(), DAILY_LIMIT);

        // Несмотря на ошибку, clear должен вызваться (finally)
        verify(sessionRepository).clear(CHAT_ID);
    }

    @Test
    void sendFinalAndClearSession_withTestVacancies_includesThemInSnapshot() {
        var testRef = new ReportSnapshot.TestVacancyRef("Java Dev", "ACME", "https://hh.ru/v/1");
        ReportSnapshot snapshot = emptySnapshot(0);
        when(vacancyRepository.requiresTestInWindow(anyLong(), any(), anyInt())).thenReturn(List.of(testRef));
        when(vacancyRepository.sessionStats(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);
        when(sessionRepository.clear(anyLong())).thenReturn(false);

        service.sendFinalAndClearSession(session(), DAILY_LIMIT);

        verify(notificationPort).sendFinalReport(eq(CHAT_ID), argThat(s ->
            !s.requiresTestVacancies().isEmpty()
        ));
    }

    @Test
    void sendFinalAndClearSession_sessionAlreadyCleared_onlyLogsNoClearLog() {
        ReportSnapshot snapshot = emptySnapshot(0);
        when(vacancyRepository.requiresTestInWindow(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(vacancyRepository.sessionStats(anyLong(), any(), any(), anyInt())).thenReturn(snapshot);
        when(sessionRepository.clear(anyLong())).thenReturn(false);

        service.sendFinalAndClearSession(session(), DAILY_LIMIT);

        verify(sessionRepository).clear(CHAT_ID);
        verify(notificationPort).sendFinalReport(anyLong(), any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static SearchSession session() {
        return new SearchSession(CHAT_ID, SESSION_START, null);
    }

    private static ReportSnapshot emptySnapshot(int applied) {
        return new ReportSnapshot(CHAT_ID, SESSION_START, NOW, applied, 0, 0, 0, 0, 0, 0, 0, DAILY_LIMIT, List.of());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
