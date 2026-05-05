package ru.hhassistant.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.domain.port.VacancyRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VacancyStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");
    private static final long CHAT_ID = 42L;

    @Mock VacancyRepository vacancyRepository;

    private VacancyStateService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new VacancyStateService();
        inject(service, "vacancyRepository", vacancyRepository);
        inject(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void countAppliedToday_delegatesToRepository_withCorrectWindow() {
        Instant expectedSince = NOW.minusSeconds(24 * 3600);
        when(vacancyRepository.countAppliedToday(anyLong(), any())).thenReturn(7);

        int result = service.countAppliedToday(CHAT_ID);

        assertThat(result).isEqualTo(7);
        // anyLong() + eq() — оба матчера (нельзя мешать raw-значения и матчеры в одном вызове)
        verify(vacancyRepository).countAppliedToday(anyLong(), eq(expectedSince));
    }

    @Test
    void countAppliedToday_zeroApplied_returnsZero() {
        when(vacancyRepository.countAppliedToday(anyLong(), any())).thenReturn(0);
        assertThat(service.countAppliedToday(CHAT_ID)).isEqualTo(0);
    }

    @Test
    void resetHistory_delegatesToRepository_returnsDeletedCount() {
        when(vacancyRepository.deleteAll(anyLong())).thenReturn(15);

        int deleted = service.resetHistory(CHAT_ID);

        assertThat(deleted).isEqualTo(15);
        verify(vacancyRepository).deleteAll(anyLong());
    }

    @Test
    void resetHistory_noHistory_returnsZero() {
        when(vacancyRepository.deleteAll(anyLong())).thenReturn(0);

        int deleted = service.resetHistory(CHAT_ID);

        assertThat(deleted).isEqualTo(0);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
