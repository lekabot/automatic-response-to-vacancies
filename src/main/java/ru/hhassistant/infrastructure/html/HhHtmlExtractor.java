package ru.hhassistant.infrastructure.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.hhassistant.domain.port.UserSettingsRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Статические утилиты для извлечения специфических данных из HTML hh.ru.
 *
 * <p>Выделены в отдельный класс от {@link HhHtmlParser} чтобы избежать смешения
 * parsing-логики с domain-сервисами. Все методы pure (без IO, без CDI).
 */
public final class HhHtmlExtractor {

    private static final Pattern RESUME_HREF_RE =
        Pattern.compile("/resume/([a-zA-Z0-9]+)");

    private HhHtmlExtractor() {}

    /**
     * Извлекает список резюме пользователя из HTML-страницы /applicant/resumes.
     * Ищет ссылки вида {@code /resume/<id>} (без "edit").
     */
    public static List<UserSettingsRepository.UserSettingsRow> extractResumes(String html) {
        if (html == null || html.isBlank()) return List.of();
        Document doc = Jsoup.parse(html);
        List<UserSettingsRepository.UserSettingsRow> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Element link : doc.select("a[href*=/resume/]")) {
            String href = link.attr("href");
            if (href.contains("edit")) continue;
            String clean = href.split("\\?")[0];
            var m = RESUME_HREF_RE.matcher(clean);
            if (!m.find()) continue;
            String resumeId = m.group(1);
            if (resumeId.length() <= 4 || seen.contains(resumeId)) continue;
            seen.add(resumeId);

            // Попытка получить название резюме из текста ссылки
            String title = extractResumeTitle(link);
            result.add(new UserSettingsRepository.UserSettingsRow(
                0L, null, null, List.of(), null, resumeId, title
            ));
        }
        return result;
    }

    /**
     * Извлекает XSRF-токен из HTML-страницы hh.ru (fallback после cookie).
     */
    public static String extractXsrfFromHtml(String html) {
        if (html == null || html.isBlank()) return null;
        Document doc = Jsoup.parse(html);
        // Вариант 1: hidden input
        Element input = doc.selectFirst("input[name=_xsrf]");
        if (input != null) {
            String val = input.val();
            if (val != null && !val.isBlank()) return val;
        }
        // Вариант 2: data-attribute на теле
        Element meta = doc.selectFirst("meta[name=csrf-token]");
        if (meta != null) {
            String content = meta.attr("content");
            if (content != null && !content.isBlank()) return content;
        }
        return null;
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private static String extractResumeTitle(Element link) {
        // Берём первый непустой текстовый узел внутри ссылки
        List<String> texts = new ArrayList<>();
        for (var node : link.textNodes()) {
            String t = node.text().strip();
            if (!t.isBlank()) texts.add(t);
        }
        if (!texts.isEmpty()) return texts.get(0);
        // Fallback: текст всей ссылки
        String full = link.text().strip();
        return full.isBlank() ? "Резюме" : full;
    }
}
