package ru.hhassistant.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Рендерит сопроводительное письмо из шаблона.
 * Поддерживаемые переменные: {@code {title}}, {@code {employer}}.
 *
 * <p>Stateless — можно использовать как singleton или static utility.
 */
public final class CoverLetterRenderer {

    private static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{([^{}]*)}");

    private CoverLetterRenderer() {}

    /**
     * Проверяет корректность шаблона: только парные фигурные скобки.
     *
     * @return {@code null} если валидно, иначе сообщение об ошибке
     */
    public static String validate(String template) {
        if (template == null || template.isBlank()) return null;
        int depth = 0;
        for (char c : template.toCharArray()) {
            if (c == '{') {
                depth++;
                if (depth > 20) return "Слишком много открывающих «{»";
            } else if (c == '}') {
                depth--;
                if (depth < 0) return "Лишняя закрывающая скобка «}»";
            }
        }
        if (depth != 0) return "Непарные фигурные скобки { }. Проверьте шаблон письма.";
        return null;
    }

    /**
     * Заменяет {@code {title}} и {@code {employer}} в шаблоне.
     * Неизвестные плейсхолдеры оставляются как есть.
     * Возвращает пустую строку если шаблон null/blank.
     */
    public static String render(String template, String title, String employer) {
        if (template == null || template.isBlank()) return "";
        String normalized = template.replace("\\n", "\n");
        Matcher m = PLACEHOLDER_RE.matcher(normalized);
        return m.replaceAll(match -> {
            String key = match.group(1).strip();
            return switch (key) {
                case "title" -> Matcher.quoteReplacement(title != null ? title : "");
                case "employer" -> Matcher.quoteReplacement(employer != null ? employer : "");
                default -> Matcher.quoteReplacement(match.group(0));
            };
        });
    }
}
