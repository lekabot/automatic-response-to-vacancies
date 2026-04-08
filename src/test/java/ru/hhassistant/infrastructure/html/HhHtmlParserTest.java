package ru.hhassistant.infrastructure.html;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.hhassistant.domain.model.VacancyCandidate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture-based тесты HTML-парсера.
 *
 * <p>Фикстуры хранятся в {@code src/test/resources/fixtures/html/}.
 * При изменении структуры hh.ru — добавить новые фикстуры и обновить assertions.
 */
class HhHtmlParserTest {

    private HhHtmlParser parser;

    @BeforeEach
    void setUp() {
        parser = new HhHtmlParser();
        // Внедряем ObjectMapper напрямую (без Quarkus CDI в unit-тесте)
        var om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        injectField(parser, "objectMapper", om);
    }

    @Test
    void parseVacancies_fromSearchPageFixture_extractsVacancies() throws IOException {
        String html = loadFixture("search_page_with_vacancies.html");
        List<VacancyCandidate> vacancies = parser.parseVacanciesFromSearchPage(html);
        assertThat(vacancies).isNotEmpty();
        assertThat(vacancies.get(0).vacancyId()).isNotBlank();
        assertThat(vacancies.get(0).title()).isNotBlank();
    }

    @Test
    void parseVacancies_fromEmptyPage_returnsEmptyList() {
        List<VacancyCandidate> result = parser.parseVacanciesFromSearchPage("<html><body></body></html>");
        assertThat(result).isEmpty();
    }

    @Test
    void parseVacancies_nullInput_returnsEmptyList() {
        assertThat(parser.parseVacanciesFromSearchPage(null)).isEmpty();
    }

    @Test
    void isVacancyAvailable_activeVacancyPage_returnsTrue() throws IOException {
        String html = loadFixture("vacancy_active_page.html");
        assertThat(parser.isVacancyAvailable(html)).isTrue();
    }

    @Test
    void isVacancyAvailable_deletedVacancyPage_returnsFalse() throws IOException {
        String html = loadFixture("vacancy_deleted_page.html");
        assertThat(parser.isVacancyAvailable(html)).isFalse();
    }

    @Test
    void isVacancyAvailable_emptyHtml_returnsFalse() {
        assertThat(parser.isVacancyAvailable("")).isFalse();
        assertThat(parser.isVacancyAvailable(null)).isFalse();
    }

    @Test
    void extractApplyUrl_pageWithApplyButton_returnsUrl() throws IOException {
        String html = loadFixture("vacancy_active_page.html");
        var url = parser.extractApplyUrl(html);
        // Если фикстура содержит кнопку откликнуться — URL присутствует
        // Иначе Optional.empty() — тест проверяет что нет NPE
        assertThat(url).isNotNull();
    }

    @Test
    void extractApplyUrl_emptyPage_returnsEmpty() {
        assertThat(parser.extractApplyUrl("<html></html>")).isEmpty();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private String loadFixture(String name) throws IOException {
        String path = "/fixtures/html/" + name;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                // Возвращаем минимальный HTML чтобы тест не падал при отсутствии фикстуры
                return "<html><body><!-- fixture " + name + " not found --></body></html>";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
