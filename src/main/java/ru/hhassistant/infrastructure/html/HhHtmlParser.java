package ru.hhassistant.infrastructure.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML-парсер страниц hh.ru через jsoup.
 *
 * <p>Используется как дополнение к JSON API в случаях, когда API недоступен
 * или не возвращает нужных данных. Структура HTML нестабильна — весь парсинг
 * строится defensively: каждое извлечение имеет fallback.
 *
 * <p>Поддерживаемые операции:
 * <ul>
 *   <li>Извлечение вакансий из {@code window.__initial_state__} на странице поиска.</li>
 *   <li>Проверка доступности страницы вакансии.</li>
 *   <li>Поиск URL формы отклика на странице вакансии.</li>
 * </ul>
 *
 * <p>Тестирование: fixture-based (сохранённые HTML-файлы в {@code src/test/resources/fixtures/html/}).
 */
@ApplicationScoped
public class HhHtmlParser {

    private static final Logger log = Logger.getLogger(HhHtmlParser.class);

    /**
     * Паттерн для извлечения window.__initial_state__ из script-тега.
     * JSON может содержать многострочные строки — используем DOTALL.
     * Regex применяется только для границ блока; само тело парсится Jackson.
     */
    private static final Pattern INITIAL_STATE_RE = Pattern.compile(
        "window\\.__initial_state__\\s*=\\s*(\\{.+?})(?:;|</script>)",
        Pattern.DOTALL
    );

    private static final List<String[]> VACANCY_PATHS = List.of(
        new String[]{"vacancySearch", "vacancies"},
        new String[]{"vacanciesSearchResult", "vacancies"},
        new String[]{"searchResult", "vacancies"}
    );

    private static final List<String> DELETED_INDICATORS = List.of(
        "вакансия удалена", "вакансия не найдена",
        "vacancy has been deleted", "vacancy not found",
        "нет активных вакансий"
    );

    @Inject ObjectMapper objectMapper;

    /**
     * Извлекает список вакансий из HTML-страницы поиска (/search/vacancy).
     * Вакансии встроены в {@code window.__initial_state__} как JSON.
     *
     * @param html HTML-контент страницы
     * @return список вакансий; пустой список если не найдено или ошибка
     */
    public List<VacancyCandidate> parseVacanciesFromSearchPage(String html) {
        if (html == null || html.isBlank()) return List.of();

        Matcher matcher = INITIAL_STATE_RE.matcher(html);
        if (!matcher.find()) {
            log.debug("html_parser.no_initial_state");
            return List.of();
        }

        JsonNode state;
        try {
            state = objectMapper.readTree(matcher.group(1));
        } catch (Exception ex) {
            log.warnf("html_parser.json_parse_error error=%s", ex.getMessage());
            return List.of();
        }

        JsonNode vacanciesNode = findVacanciesNode(state);
        if (vacanciesNode == null || !vacanciesNode.isArray()) {
            log.debug("html_parser.vacancies_not_found_in_state");
            return List.of();
        }

        List<VacancyCandidate> result = new ArrayList<>();
        for (JsonNode item : vacanciesNode) {
            parseVacancyFromState(item).ifPresent(result::add);
        }
        log.debugf("html_parser.parsed_vacancies count=%d", result.size());
        return result;
    }

    /**
     * Проверяет, доступна ли вакансия (не удалена, не закрыта).
     *
     * @param html HTML-контент страницы вакансии
     * @return {@code true} если вакансия активна
     */
    public boolean isVacancyAvailable(String html) {
        if (html == null || html.isBlank()) return false;
        Document doc = Jsoup.parse(html);
        String text = doc.body().text().toLowerCase();
        return DELETED_INDICATORS.stream().noneMatch(text::contains);
    }

    /**
     * Извлекает URL формы отклика со страницы вакансии.
     *
     * @param html HTML-контент страницы вакансии
     * @return URL формы или {@code Optional.empty()} если не найдено
     */
    public Optional<String> extractApplyUrl(String html) {
        if (html == null || html.isBlank()) return Optional.empty();
        Document doc = Jsoup.parse(html);

        // Приоритет: прямая кнопка "Откликнуться"
        for (Element el : doc.select("a[href], button")) {
            String text = el.text().trim().toLowerCase();
            if (text.contains("откликнуться") || text.contains("respond")) {
                String href = el.attr("href");
                if (href != null && href.startsWith("http")) return Optional.of(href);
            }
        }

        // Fallback: data-qa атрибут (типичен для hh.ru)
        Element applyBtn = doc.selectFirst("[data-qa='vacancy-response-link-top']");
        if (applyBtn != null) {
            String href = applyBtn.attr("href");
            if (!href.isBlank()) {
                return Optional.of(href.startsWith("http") ? href : "https://hh.ru" + href);
            }
        }
        return Optional.empty();
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private JsonNode findVacanciesNode(JsonNode state) {
        for (String[] path : VACANCY_PATHS) {
            JsonNode node = state;
            for (String key : path) {
                if (node == null || !node.isObject()) { node = null; break; }
                node = node.path(key);
                if (node.isMissingNode()) { node = null; break; }
            }
            if (node != null && node.isArray()) return node;
        }
        return null;
    }

    private Optional<VacancyCandidate> parseVacancyFromState(JsonNode node) {
        try {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) return Optional.empty();
            String title = node.path("name").asText("");
            String employer = node.path("employer").path("name").asText("");
            String url = node.path("alternateUrl").asText(
                node.path("alternate_url").asText("https://hh.ru/vacancy/" + id));
            boolean hasTest = node.path("hasTest").asBoolean(
                node.path("has_test").asBoolean(false));
            String area = node.path("area").path("name").asText("");
            return Optional.of(new VacancyCandidate(id, title, employer, url, null, hasTest, area));
        } catch (Exception ex) {
            log.debugf("html_parser.vacancy_node_error error=%s", ex.getMessage());
            return Optional.empty();
        }
    }
}
