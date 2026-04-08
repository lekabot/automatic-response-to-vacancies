package ru.hhassistant.adapter.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.hhassistant.domain.model.ReportSnapshot;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramMessageFormatterTest {

    private TelegramMessageFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new TelegramMessageFormatter();
    }

    @Test
    void formatHourlyReport_containsAppliedCount() {
        ReportSnapshot snapshot = snapshot(5, 0, 0, 0, 0, 0, 0, 200, List.of());
        String text = formatter.formatHourlyReport(snapshot);
        assertThat(text).contains("5").contains("200");
    }

    @Test
    void formatFinalReport_containsFinalHeader() {
        ReportSnapshot snapshot = snapshot(10, 2, 3, 1, 0, 0, 0, 200, List.of());
        String text = formatter.formatFinalReport(snapshot);
        assertThat(text).contains("Итог сессии");
        assertThat(text).contains("10");
    }

    @Test
    void formatReport_withRequiresTest_listsVacancies() {
        var testVacs = List.of(
            new ReportSnapshot.TestVacancyRef("Java Dev", "ACME", "https://hh.ru/vacancy/123"),
            new ReportSnapshot.TestVacancyRef("Python Dev", "BigCo", "https://hh.ru/vacancy/456")
        );
        ReportSnapshot snapshot = snapshot(0, 0, 0, 2, 0, 0, 0, 200, testVacs);
        String text = formatter.formatFinalReport(snapshot);
        assertThat(text).contains("Java Dev").contains("ACME");
        assertThat(text).contains("Python Dev");
    }

    @Test
    void htmlEscape_escapesSpecialChars() {
        assertThat(TelegramMessageFormatter.esc("A&B<C>D")).isEqualTo("A&amp;B&lt;C&gt;D");
    }

    @Test
    void htmlEscape_nullReturnsEmpty() {
        assertThat(TelegramMessageFormatter.esc(null)).isEmpty();
    }

    @Test
    void formatSettingsMenu_displaysKeywords() {
        String text = formatter.formatSettingsMenu(
            List.of("Python dev", "Java backend"), true, "test@mail.ru", "Резюме 1");
        assertThat(text).contains("Python dev");
        assertThat(text).contains("test@mail.ru");
        assertThat(text).contains("Резюме 1");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static ReportSnapshot snapshot(
        int applied, int alreadyApplied, int skipped, int requiresTest,
        int timeout, int tempErr, int permErr, int limit,
        List<ReportSnapshot.TestVacancyRef> testVacs
    ) {
        return new ReportSnapshot(
            1L, Instant.EPOCH, Instant.now(),
            applied, alreadyApplied, skipped, requiresTest,
            timeout, tempErr, permErr, 0, limit, testVacs
        );
    }
}
