package ru.hhassistant.infrastructure.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.hhassistant.domain.port.UserSettingsRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class HhHtmlExtractor {
  private static final Pattern RESUME_HREF_RE = Pattern.compile("/resume/([a-zA-Z0-9]+)");

  private HhHtmlExtractor() {
  }

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

      String title = extractResumeTitle(link);
      result.add(new UserSettingsRepository.UserSettingsRow(
        0L, null, null, List.of(), null, resumeId, title
      ));
    }
    return result;
  }

  public static String extractXsrfFromHtml(String html) {
    if (html == null || html.isBlank()) return null;
    Document doc = Jsoup.parse(html);
    Element input = doc.selectFirst("input[name=_xsrf]");
    if (input != null) {
      String val = input.val();
      if (val != null && !val.isBlank()) return val;
    }
    Element meta = doc.selectFirst("meta[name=csrf-token]");
    if (meta != null) {
      String content = meta.attr("content");
      if (content != null && !content.isBlank()) return content;
    }
    return null;
  }

  private static String extractResumeTitle(Element link) {
    List<String> texts = new ArrayList<>();
    for (var node : link.textNodes()) {
      var t = node.text().strip();
      if (!t.isBlank()) texts.add(t);
    }
    if (!texts.isEmpty()) return texts.get(0);
    var full = link.text().strip();
    return full.isBlank() ? "Резюме" : full;
  }
}
