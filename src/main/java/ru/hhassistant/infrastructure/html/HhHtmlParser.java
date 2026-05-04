package ru.hhassistant.infrastructure.html;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.hhassistant.domain.model.VacancyCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@ApplicationScoped
public class HhHtmlParser {
  @Inject
  ObjectMapper objectMapper;

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
      log.warn("html_parser.json_parse_error error={}", ex.getMessage());
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
    log.debug("html_parser.parsed_vacancies count={}", result.size());
    return result;
  }

  public boolean isVacancyAvailable(String html) {
    if (html == null || html.isBlank()) return false;
    Document doc = Jsoup.parse(html);
    String text = doc.body().text().toLowerCase();
    return DELETED_INDICATORS.stream().noneMatch(text::contains);
  }

  public Optional<String> extractApplyUrl(String html) {
    if (html == null || html.isBlank()) return Optional.empty();
    Document doc = Jsoup.parse(html);

    for (Element el : doc.select("a[href], button")) {
      String text = el.text().trim().toLowerCase();
      if (text.contains("откликнуться") || text.contains("respond")) {
        String href = el.attr("href");
        if (href.startsWith("http")) return Optional.of(href);
      }
    }

    Element applyBtn = doc.selectFirst("[data-qa='vacancy-response-link-top']");
    if (applyBtn != null) {
      String href = applyBtn.attr("href");
      if (!href.isBlank()) {
        return Optional.of(href.startsWith("http") ? href : "https://hh.ru" + href);
      }
    }
    return Optional.empty();
  }

  private JsonNode findVacanciesNode(JsonNode state) {
    for (String[] path : VACANCY_PATHS) {
      JsonNode node = state;
      for (String key : path) {
        if (node == null || !node.isObject()) {
          node = null;
          break;
        }
        node = node.path(key);
        if (node.isMissingNode()) {
          node = null;
          break;
        }
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
      log.debug("html_parser.vacancy_node_error error={}", ex.getMessage());
      return Optional.empty();
    }
  }
}
