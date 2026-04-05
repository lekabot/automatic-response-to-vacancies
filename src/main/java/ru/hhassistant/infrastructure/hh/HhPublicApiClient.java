package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.jboss.logging.Logger;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Клиент публичного REST API hh.ru (api.hh.ru).
 *
 * <p>Не требует аутентификации для поиска вакансий.
 * Пагинация: page/per_page, максимум 2000 вакансий (hh.ru limit).
 */
@ApplicationScoped
public class HhPublicApiClient {

    private static final Logger log = Logger.getLogger(HhPublicApiClient.class);
    private static final String API_BASE = "https://api.hh.ru";
    private static final int PER_PAGE = 50;

    @Inject RateLimitedHttpExecutor httpExecutor;
    @Inject HhConfig hhConfig;
    @Inject ObjectMapper objectMapper;

    /**
     * Ищет все вакансии по одному keyword-запросу, собирая все страницы.
     *
     * @param keyword         строка поискового запроса
     * @param areas           список ID регионов (1=Москва, 2=СПб, 113=Россия)
     * @param schedules       graphics: remote, fullDay, etc.
     * @param employmentTypes full, part, etc.
     * @param searchField     null = по умолчанию
     * @param period          дни публикации
     * @param maxVacancies    жёсткий cap на количество результатов
     * @return список вакансий-кандидатов, не превышающий maxVacancies
     */
    public List<VacancyCandidate> searchAll(
        String keyword,
        List<Integer> areas,
        List<String> schedules,
        List<String> employmentTypes,
        String searchField,
        int period,
        int maxVacancies
    ) throws IOException {
        List<VacancyCandidate> result = new ArrayList<>();
        int page = 0;
        int totalPages = 1;

        while (page < totalPages && result.size() < maxVacancies) {
            JsonNode responseNode = fetchPage(keyword, areas, schedules, employmentTypes,
                searchField, period, page);

            totalPages = responseNode.path("pages").asInt(1);
            JsonNode items = responseNode.path("items");
            if (!items.isArray() || items.isEmpty()) break;

            for (JsonNode item : items) {
                if (result.size() >= maxVacancies) break;
                parseVacancy(item).ifPresent(result::add);
            }
            log.debugf("api.page_fetched keyword='%s' page=%d total_pages=%d fetched=%d",
                keyword, page, totalPages, items.size());
            page++;
        }
        return result;
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private JsonNode fetchPage(
        String keyword,
        List<Integer> areas,
        List<String> schedules,
        List<String> employmentTypes,
        String searchField,
        int period,
        int page
    ) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(API_BASE + "/vacancies").newBuilder()
            .addQueryParameter("text", keyword)
            .addQueryParameter("period", String.valueOf(period))
            .addQueryParameter("page", String.valueOf(page))
            .addQueryParameter("per_page", String.valueOf(PER_PAGE));

        for (Integer area : areas) urlBuilder.addQueryParameter("area", String.valueOf(area));
        for (String s : schedules) urlBuilder.addQueryParameter("schedule", s);
        for (String e : employmentTypes) urlBuilder.addQueryParameter("employment", e);
        if (searchField != null && !searchField.isBlank()) {
            urlBuilder.addQueryParameter("search_field", searchField);
        }

        Request request = new Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", hhConfig.userAgent())
            .header("Accept", "application/json")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .get()
            .build();

        try (Response response = httpExecutor.execute(request)) {
            if (!response.isSuccessful()) {
                throw new IOException("HH API error: " + response.code() + " for keyword=" + keyword);
            }
            String body = response.body() != null ? response.body().string() : "{}";
            return objectMapper.readTree(body);
        }
    }

    private Optional<VacancyCandidate> parseVacancy(JsonNode node) {
        try {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) return Optional.empty();

            String title = node.path("name").asText("");
            JsonNode employerNode = node.path("employer");
            String employer = employerNode.path("name").asText("");
            String url = node.path("alternate_url").asText(
                "https://hh.ru/vacancy/" + id);

            String salaryText = parseSalaryText(node.path("salary"));
            boolean hasTest = node.path("has_test").asBoolean(false);
            String areaName = node.path("area").path("name").asText("");

            return Optional.of(new VacancyCandidate(id, title, employer, url, salaryText, hasTest, areaName));
        } catch (Exception ex) {
            log.debugf("api.vacancy_parse_error: %s", ex.getMessage());
            return Optional.empty();
        }
    }

    private String parseSalaryText(JsonNode salary) {
        if (salary == null || salary.isNull() || salary.isMissingNode()) return null;
        JsonNode from = salary.path("from");
        JsonNode to = salary.path("to");
        String currency = salary.path("currency").asText("RUR");
        String gross = salary.path("gross").asBoolean(false) ? " до вычета налогов" : "";

        if (from.isNull() && to.isNull()) return null;
        if (!from.isNull() && !to.isNull()) {
            return "от " + from.asLong() + " до " + to.asLong() + " " + currency + gross;
        }
        if (!from.isNull()) return "от " + from.asLong() + " " + currency + gross;
        return "до " + to.asLong() + " " + currency + gross;
    }
}
