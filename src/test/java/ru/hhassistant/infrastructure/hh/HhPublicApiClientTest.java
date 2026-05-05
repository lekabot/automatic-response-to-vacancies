package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HhPublicApiClientTest {

    @Mock RateLimitedHttpExecutor httpExecutor;
    @Mock HhConfig hhConfig;

    private HhPublicApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new HhPublicApiClient();
        inject(client, "httpExecutor", httpExecutor);
        inject(client, "hhConfig", hhConfig);
        inject(client, "objectMapper", new ObjectMapper());
        lenient().when(hhConfig.userAgent()).thenReturn("TestAgent/1.0");
    }

    // ─── searchAll ────────────────────────────────────────────────────────────

    @Test
    void searchAll_singlePage_parsesVacancies() throws IOException {
        String body = """
            {
              "pages": 1,
              "items": [
                {
                  "id": "12345",
                  "name": "Java разработчик",
                  "employer": {"name": "ACME"},
                  "alternate_url": "https://hh.ru/vacancy/12345",
                  "salary": {"from": 150000, "to": null, "currency": "RUR", "gross": false},
                  "has_test": false,
                  "area": {"name": "Москва"}
                }
              ]
            }""";
        stubResponse(200, body);

        List<VacancyCandidate> result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50);

        assertThat(result).hasSize(1);
        VacancyCandidate v = result.get(0);
        assertThat(v.vacancyId()).isEqualTo("12345");
        assertThat(v.title()).isEqualTo("Java разработчик");
        assertThat(v.employer()).isEqualTo("ACME");
        assertThat(v.salaryText()).isEqualTo("от 150000 RUR");
        assertThat(v.hasTest()).isFalse();
        assertThat(v.areaName()).isEqualTo("Москва");
    }

    @Test
    void searchAll_emptyItems_returnsEmpty() throws IOException {
        stubResponse(200, "{\"pages\": 1, \"items\": []}");

        List<VacancyCandidate> result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50);

        assertThat(result).isEmpty();
    }

    @Test
    void searchAll_httpError_throwsIOException() throws IOException {
        stubResponse(429, "Rate limit exceeded");

        assertThatThrownBy(() -> client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("429");
    }

    @Test
    void searchAll_vacancyWithoutId_skipped() throws IOException {
        String body = """
            {
              "pages": 1,
              "items": [
                {"id": "", "name": "No ID vacancy"},
                {"id": "99999", "name": "Valid vacancy", "employer": {"name": "Co"},
                 "alternate_url": "https://hh.ru/vacancy/99999", "has_test": false, "area": {"name": ""}}
              ]
            }""";
        stubResponse(200, body);

        List<VacancyCandidate> result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).vacancyId()).isEqualTo("99999");
    }

    @Test
    void searchAll_cappedAtMaxVacancies() throws IOException {
        // Creates a response with 5 items but maxVacancies = 2
        StringBuilder items = new StringBuilder("[");
        for (int i = 1; i <= 5; i++) {
            if (i > 1) items.append(",");
            items.append("""
                {"id": "%d", "name": "Vacancy %d", "employer": {"name": "Co"},
                 "alternate_url": "https://hh.ru/vacancy/%d", "has_test": false, "area": {"name": ""}}
                """.formatted(i, i, i));
        }
        items.append("]");
        stubResponse(200, "{\"pages\": 1, \"items\": " + items + "}");

        List<VacancyCandidate> result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 2);

        assertThat(result).hasSize(2);
    }

    // ─── salary parsing ───────────────────────────────────────────────────────

    @Test
    void searchAll_salaryFromAndTo_formatsCorrectly() throws IOException {
        String body = """
            {"pages": 1, "items": [
              {"id": "1", "name": "Dev", "employer": {"name": "Co"},
               "alternate_url": "url", "has_test": false, "area": {"name": ""},
               "salary": {"from": 100000, "to": 200000, "currency": "RUR", "gross": false}}
            ]}""";
        stubResponse(200, body);

        var result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50);
        assertThat(result.get(0).salaryText()).isEqualTo("от 100000 до 200000 RUR");
    }

    @Test
    void searchAll_salaryToOnly_formatsCorrectly() throws IOException {
        String body = """
            {"pages": 1, "items": [
              {"id": "2", "name": "Dev", "employer": {"name": "Co"},
               "alternate_url": "url", "has_test": false, "area": {"name": ""},
               "salary": {"from": null, "to": 180000, "currency": "USD", "gross": true}}
            ]}""";
        stubResponse(200, body);

        var result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50);
        assertThat(result.get(0).salaryText()).isEqualTo("до 180000 USD до вычета налогов");
    }

    @Test
    void searchAll_noSalary_returnsNullSalaryText() throws IOException {
        String body = """
            {"pages": 1, "items": [
              {"id": "3", "name": "Dev", "employer": {"name": "Co"},
               "alternate_url": "url", "has_test": false, "area": {"name": ""}}
            ]}""";
        stubResponse(200, body);

        var result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50);
        assertThat(result.get(0).salaryText()).isNull();
    }

    @Test
    void searchAll_hasTestVacancy_parsedCorrectly() throws IOException {
        String body = """
            {"pages": 1, "items": [
              {"id": "5", "name": "Dev", "employer": {"name": "Co"},
               "alternate_url": "url", "has_test": true, "area": {"name": "СПб"}}
            ]}""";
        stubResponse(200, body);

        var result = client.searchAll("java", List.of(1), List.of(), List.of(), null, 1, 50);
        assertThat(result.get(0).hasTest()).isTrue();
        assertThat(result.get(0).areaName()).isEqualTo("СПб");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void stubResponse(int code, String body) throws IOException {
        Request req = new Request.Builder().url("https://api.hh.ru/vacancies").build();
        Response response = new Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
            .build();
        when(httpExecutor.execute(any())).thenReturn(response);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
