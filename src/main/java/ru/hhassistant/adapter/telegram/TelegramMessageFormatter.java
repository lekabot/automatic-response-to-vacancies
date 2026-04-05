package ru.hhassistant.adapter.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import ru.hhassistant.domain.model.ReportSnapshot;

import java.util.List;

/**
 * Форматирует domain-объекты в Telegram HTML-сообщения.
 * Stateless, тестируется без инфраструктуры.
 */
@ApplicationScoped
public class TelegramMessageFormatter {

    public String formatHourlyReport(ReportSnapshot s) {
        return formatReport(s, false);
    }

    public String formatFinalReport(ReportSnapshot s) {
        return formatReport(s, true);
    }

    private String formatReport(ReportSnapshot s, boolean isFinal) {
        String header = isFinal
            ? "🏁 <b>Итог сессии</b>"
            : "📊 <b>Часовой отчёт</b>";

        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n\n");
        sb.append("✅ Откликнулся: <b>").append(s.applied()).append("</b>");
        if (s.dailyLimit() > 0) {
            sb.append(" / ").append(s.dailyLimit());
        }
        sb.append("\n");

        if (s.alreadyApplied() > 0)
            sb.append("🔁 Уже откликался: ").append(s.alreadyApplied()).append("\n");
        if (s.skipped() > 0)
            sb.append("⏭ Пропущено: ").append(s.skipped()).append("\n");
        if (s.requiresTest() > 0)
            sb.append("📝 Нужен тест: ").append(s.requiresTest()).append("\n");
        if (s.retryLater() > 0)
            sb.append("🔄 Повтор позже: ").append(s.retryLater()).append("\n");
        if (s.applyPermError() > 0)
            sb.append("❌ Постоянная ошибка: ").append(s.applyPermError()).append("\n");

        List<ReportSnapshot.TestVacancyRef> tests = s.requiresTestVacancies();
        if (tests != null && !tests.isEmpty()) {
            sb.append("\n<b>Вакансии с тестами (").append(s.requiresTest()).append("):</b>\n");
            int shown = Math.min(tests.size(), 10);
            for (int i = 0; i < shown; i++) {
                ReportSnapshot.TestVacancyRef t = tests.get(i);
                sb.append("• <a href=\"").append(esc(t.url())).append("\">")
                  .append(esc(t.title())).append("</a>")
                  .append(" — ").append(esc(t.employer())).append("\n");
            }
            if (tests.size() > shown) {
                sb.append("… и ещё ").append(tests.size() - shown).append("\n");
            }
        }

        return sb.toString();
    }

    public String formatSettingsMenu(
        List<String> keywords, boolean hasCoverLetter, String email, String resumeTitle
    ) {
        String kwStr = keywords.isEmpty() ? "—"
            : String.join(", ", keywords.subList(0, Math.min(5, keywords.size())))
              + (keywords.size() > 5 ? " (+" + (keywords.size() - 5) + ")" : "");
        return """
            ⚙️ <b>Настройки поиска</b>
            
            🔍 <b>Ключевые слова:</b> %s
            ✉️ <b>Письмо:</b> %s
            👤 <b>Аккаунт hh.ru:</b> %s
            📄 <b>Резюме:</b> %s
            
            Измените настройки или запустите поиск:"""
            .formatted(esc(kwStr), hasCoverLetter ? "Да" : "Нет",
                esc(email != null ? email : "—"),
                esc(resumeTitle != null ? resumeTitle : "—"));
    }

    // ─── HTML escaping ────────────────────────────────────────────────────────

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
