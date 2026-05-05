package ru.hhassistant.infrastructure.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.hhassistant.domain.port.UserSettingsRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HhHtmlExtractorTest {

    // ─── extractXsrfFromHtml ───────────────────────────────────────────────────

    @Test
    void extractXsrf_fromInputNameXsrf_returnsValue() {
        String html = "<html><body><input name=\"_xsrf\" value=\"token-abc123xyz\"></body></html>";
        assertThat(HhHtmlExtractor.extractXsrfFromHtml(html)).isEqualTo("token-abc123xyz");
    }

    @Test
    void extractXsrf_fromMetaCsrfToken_returnsValue() {
        String html = "<html><head><meta name=\"csrf-token\" content=\"meta-token-456\"></head></html>";
        assertThat(HhHtmlExtractor.extractXsrfFromHtml(html)).isEqualTo("meta-token-456");
    }

    @Test
    void extractXsrf_inputTakesPriorityOverMeta() {
        String html = "<html><head>"
            + "<meta name=\"csrf-token\" content=\"meta-value\">"
            + "</head><body>"
            + "<input name=\"_xsrf\" value=\"input-value\">"
            + "</body></html>";
        assertThat(HhHtmlExtractor.extractXsrfFromHtml(html)).isEqualTo("input-value");
    }

    @Test
    void extractXsrf_noXsrfAnywhere_returnsNull() {
        String html = "<html><body><form><input name=\"email\" value=\"test@mail.ru\"></form></body></html>";
        assertThat(HhHtmlExtractor.extractXsrfFromHtml(html)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void extractXsrf_blankOrEmpty_returnsNull(String html) {
        assertThat(HhHtmlExtractor.extractXsrfFromHtml(html)).isNull();
    }

    @Test
    void extractXsrf_nullInput_returnsNull() {
        assertThat(HhHtmlExtractor.extractXsrfFromHtml(null)).isNull();
    }

    @Test
    void extractXsrf_inputWithEmptyValue_fallsBackToMeta() {
        String html = "<html><head><meta name=\"csrf-token\" content=\"meta-val\"></head>"
            + "<body><input name=\"_xsrf\" value=\"\"></body></html>";
        assertThat(HhHtmlExtractor.extractXsrfFromHtml(html)).isEqualTo("meta-val");
    }

    // ─── extractResumes ───────────────────────────────────────────────────────

    @Test
    void extractResumes_singleResumeLink_returnsParsedEntry() {
        String html = "<html><body>"
            + "<a href=\"/resume/abcdef123456789\">Java разработчик</a>"
            + "</body></html>";
        List<UserSettingsRepository.UserSettingsRow> result = HhHtmlExtractor.extractResumes(html);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).resumeId()).isEqualTo("abcdef123456789");
        assertThat(result.get(0).resumeTitle()).isEqualTo("Java разработчик");
    }

    @Test
    void extractResumes_editLinksAreFiltered() {
        String html = "<html><body>"
            + "<a href=\"/resume/abc12345/edit\">Редактировать</a>"
            + "<a href=\"/resume/abc12345\">Java Dev</a>"
            + "</body></html>";
        List<UserSettingsRepository.UserSettingsRow> result = HhHtmlExtractor.extractResumes(html);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).resumeId()).isEqualTo("abc12345");
    }

    @Test
    void extractResumes_duplicateLinksDeduped() {
        String html = "<html><body>"
            + "<a href=\"/resume/abc12345678\">Python Dev</a>"
            + "<a href=\"/resume/abc12345678?from=other\">Python Dev</a>"
            + "</body></html>";
        List<UserSettingsRepository.UserSettingsRow> result = HhHtmlExtractor.extractResumes(html);
        assertThat(result).hasSize(1);
    }

    @Test
    void extractResumes_shortResumeId_ignored() {
        String html = "<html><body>"
            + "<a href=\"/resume/ab\">Short ID</a>"
            + "<a href=\"/resume/validid12345678\">Valid Resume</a>"
            + "</body></html>";
        List<UserSettingsRepository.UserSettingsRow> result = HhHtmlExtractor.extractResumes(html);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).resumeId()).isEqualTo("validid12345678");
    }

    @Test
    void extractResumes_emptyHtml_returnsEmpty() {
        assertThat(HhHtmlExtractor.extractResumes("<html></html>")).isEmpty();
    }

    @Test
    void extractResumes_nullHtml_returnsEmpty() {
        assertThat(HhHtmlExtractor.extractResumes(null)).isEmpty();
    }

    @Test
    void extractResumes_linkWithQueryParam_stripsQuery() {
        String html = "<html><body>"
            + "<a href=\"/resume/qwerty12345678?from=main\">Backend Dev</a>"
            + "</body></html>";
        List<UserSettingsRepository.UserSettingsRow> result = HhHtmlExtractor.extractResumes(html);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).resumeId()).isEqualTo("qwerty12345678");
    }

    @Test
    void extractResumes_noTitleText_defaultsToFallback() {
        String html = "<html><body>"
            + "<a href=\"/resume/nnn999nnn999nnn\"><img src=\"img.png\"/></a>"
            + "</body></html>";
        List<UserSettingsRepository.UserSettingsRow> result = HhHtmlExtractor.extractResumes(html);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).resumeTitle()).isEqualTo("Резюме");
    }
}
