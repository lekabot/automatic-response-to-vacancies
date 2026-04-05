package ru.hhassistant.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.hhassistant.domain.model.SearchSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSessionTest {

    private static final Instant START = Instant.parse("2026-04-01T10:00:00Z");

    @ParameterizedTest
    @CsvSource({
        "0,  0",   // ровно старт
        "3599, 0", // 59 мин 59 сек — ещё нулевой слот
        "3600, 1", // ровно 1 час — первый слот
        "7199, 1", // 1 час 59 мин
        "7200, 2", // 2 часа
    })
    void currentHourlySlot_calculatedCorrectly(long elapsedSeconds, int expectedSlot) {
        SearchSession session = new SearchSession(1L, START, null);
        Instant now = START.plusSeconds(elapsedSeconds);
        assertThat(session.currentHourlySlot(now)).isEqualTo(expectedSlot);
    }

    @Test
    void isHourlyReportDue_whenSlot0_notDue() {
        SearchSession session = new SearchSession(1L, START, null);
        Instant now = START.plusSeconds(1800); // 30 минут
        assertThat(session.isHourlyReportDue(now)).isFalse();
    }

    @Test
    void isHourlyReportDue_whenSlot1AndNoLastSlot_isDue() {
        SearchSession session = new SearchSession(1L, START, null);
        Instant now = START.plusSeconds(3700); // 1 час 1 мин
        assertThat(session.isHourlyReportDue(now)).isTrue();
    }

    @Test
    void isHourlyReportDue_whenAlreadySentSlot1_notDue() {
        SearchSession session = new SearchSession(1L, START, 1);
        Instant now = START.plusSeconds(3700);
        assertThat(session.isHourlyReportDue(now)).isFalse();
    }

    @Test
    void isHourlyReportDue_whenSlot2ButLastWas1_isDue() {
        SearchSession session = new SearchSession(1L, START, 1);
        Instant now = START.plusSeconds(7300); // 2 часа 1 мин
        assertThat(session.isHourlyReportDue(now)).isTrue();
    }
}
