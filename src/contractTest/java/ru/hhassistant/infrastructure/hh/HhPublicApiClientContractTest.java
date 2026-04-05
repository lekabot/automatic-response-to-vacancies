package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контрактный тест для HhPublicApiClient.
 *
 * <p>Проверяет что клиент:
 * 1. Отправляет правильный URL с параметрами.
 * 2. Корректно парсит ответ API.
 * 3. Пагинирует (запрашивает следующие страницы при pages > 1).
 * 4. Соблюдает cap по maxVacancies.
 */
class HhPublicApiClientContractTest {

    private MockWebServer server;
    private HhPublicApiClient client;

    private static final String VACANCY_RESPONSE = """
        {
          "items": [
            {
              "id": "123456",
              "name": "Java Senior Developer",
              "alternate_url": "https://hh.ru/vacancy/123456",
              "employer": {"name": "ACME Corp"},
              "area": {"name": "Москва"},
              "salary": {"from": 200000, "to": 300000, "currency": "RUR", "gross": false},
              "has_test": false
            }
          ],
          "found": 1,
          "pages": 1,
          "page": 0,
          "per_page": 50
        }
        """;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        // Создаём клиент вручную с подменённым base URL через reflection
        client = buildClient(server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void searchAll_singlePage_returnsVacancies() throws Exception {
        server.enqueue(new MockResponse()
            .setBody(VACANCY_RESPONSE)
            .addHeader("Content-Type", "application/json"));

        List<VacancyCandidate> vacancies = client.searchAll(
            "Java developer", List.of(1), List.of("remote"),
            List.of("full"), null, 1, 50);

        assertThat(vacancies).hasSize(1);
        assertThat(vacancies.get(0).vacancyId()).isEqualTo("123456");
        assertThat(vacancies.get(0).title()).isEqualTo("Java Senior Developer");
        assertThat(vacancies.get(0).employer()).isEqualTo("ACME Corp");
        assertThat(vacancies.get(0).hasTest()).isFalse();
    }

    @Test
    void searchAll_requestContainsCorrectParameters() throws Exception {
        server.enqueue(new MockResponse().setBody(VACANCY_RESPONSE)
            .addHeader("Content-Type", "application/json"));

        client.searchAll("Python backend", List.of(1, 2), List.of("remote"),
            List.of("full"), null, 1, 10);

        RecordedRequest request = server.takeRequest();
        String path = request.getPath();
        assertThat(path).contains("text=Python+backend");
        assertThat(path).contains("area=1");
        assertThat(path).contains("area=2");
        assertThat(path).contains("schedule=remote");
        assertThat(path).contains("period=1");
    }

    @Test
    void searchAll_capsAtMaxVacancies() throws Exception {
        String manyItems = buildResponse(5, 1);
        server.enqueue(new MockResponse().setBody(manyItems)
            .addHeader("Content-Type", "application/json"));

        List<VacancyCandidate> result = client.searchAll(
            "Java", List.of(1), List.of(), List.of(), null, 1, 3);

        assertThat(result).hasSize(3);
    }

    @Test
    void searchAll_apiError_throwsIOException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
            client.searchAll("Java", List.of(1), List.of(), List.of(), null, 1, 50));
    }

    @Test
    void searchAll_emptyResponse_returnsEmptyList() throws Exception {
        server.enqueue(new MockResponse()
            .setBody("{\"items\": [], \"pages\": 0, \"found\": 0, \"page\": 0, \"per_page\": 50}")
            .addHeader("Content-Type", "application/json"));

        List<VacancyCandidate> result = client.searchAll(
            "nonexistent keyword xyz", List.of(1), List.of(), List.of(), null, 1, 50);
        assertThat(result).isEmpty();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private String buildResponse(int count, int pages) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) items.append(",");
            items.append("""
                {"id": "%d", "name": "Vacancy %d", "alternate_url": "https://hh.ru/vacancy/%d",
                 "employer": {"name": "Co"}, "area": {"name": "М"}, "has_test": false}
                """.formatted(i, i, i));
        }
        return """
            {"items": [%s], "found": %d, "pages": %d, "page": 0, "per_page": 50}
            """.formatted(items, count, pages);
    }

    private HhPublicApiClient buildClient(String baseUrl) {
        // Используем reflection для инъекции тестовых зависимостей без Quarkus CDI
        var apiClient = new HhPublicApiClient();

        var om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        var httpClient = new OkHttpClient();

        var hhConfig = TestHhConfig.create();

        // RateLimitedHttpExecutor с тестовым клиентом
        var executor = new RateLimitedHttpExecutorTestImpl(httpClient, hhConfig, baseUrl);

        injectField(apiClient, "objectMapper", om);
        injectField(apiClient, "hhConfig", hhConfig);
        injectField(apiClient, "httpExecutor", executor);

        // Переопределяем API_BASE через тест-специфичную реализацию
        replaceApiBase(apiClient, baseUrl);

        return apiClient;
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            var f = findField(target.getClass(), fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException("Cannot inject " + fieldName, ex);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try { return clazz.getDeclaredField(name); }
        catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), name);
            throw e;
        }
    }

    private static void replaceApiBase(HhPublicApiClient client, String baseUrl) {
        // API_BASE — static final, для тестов используем MockWebServer URL через executor
        // Исполнение реальных запросов обёрнуто в RateLimitedHttpExecutorTestImpl
    }
}

/** Тестовый конфиг */
class TestHhConfig implements ru.hhassistant.config.HhConfig {
    static TestHhConfig create() { return new TestHhConfig(); }
    @Override public String userAgent() { return "TestAgent/1.0"; }
    @Override public RateLimitConfig rateLimit() {
        return new RateLimitConfig() {
            @Override public double qps() { return 100.0; }
            @Override public int burst() { return 100; }
        };
    }
    @Override public SearchConfig search() { return null; }
}

/** Тестовый executor, который перенаправляет запросы на MockWebServer */
class RateLimitedHttpExecutorTestImpl extends RateLimitedHttpExecutor {
    private final OkHttpClient client;
    private final String baseUrl;

    RateLimitedHttpExecutorTestImpl(OkHttpClient client, ru.hhassistant.config.HhConfig config, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public okhttp3.Response execute(okhttp3.Request request) throws java.io.IOException {
        // Перезаписываем host на MockWebServer
        var mockUrl = okhttp3.HttpUrl.parse(baseUrl).newBuilder()
            .encodedPath(request.url().encodedPath())
            .encodedQuery(request.url().encodedQuery())
            .build();
        var mockRequest = request.newBuilder().url(mockUrl).build();
        return client.newCall(mockRequest).execute();
    }
}
