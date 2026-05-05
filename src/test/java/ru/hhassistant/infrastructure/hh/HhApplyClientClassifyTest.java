package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для статической функции classifyResponse.
 * Тестируется логика классификации без HTTP-запросов.
 */
class HhApplyClientClassifyTest {

    private final ObjectMapper om = new ObjectMapper();

    // ─── HTTP status codes ─────────────────────────────────────────────────────

    @Test
    void classify_429_tempErrorRetryable() {
        var outcome = HhApplyClient.classifyResponse("v1", 429, "", null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo("http_429");
    }

    @ParameterizedTest
    @CsvSource({"401", "403"})
    void classify_authError_notRetryable(int code) {
        var outcome = HhApplyClient.classifyResponse("v1", code, "", null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.AUTH_ERROR);
        assertThat(outcome.retryable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"500", "502", "503"})
    void classify_5xx_tempErrorRetryable(int code) {
        var outcome = HhApplyClient.classifyResponse("v1", code, "", null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }

    // ─── JSON body — scalar error ──────────────────────────────────────────────

    @Test
    void classify_jsonAlreadyApplied_returnsAlreadyApplied() throws Exception {
        ObjectNode json = om.createObjectNode();
        json.put("error", "alreadyApplied");
        var outcome = HhApplyClient.classifyResponse("v1", 200, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.ALREADY_APPLIED);
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void classify_jsonSuccess_returnsApplied() {
        ObjectNode json = om.createObjectNode();
        json.put("success", true);
        var outcome = HhApplyClient.classifyResponse("v1", 200, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.APPLIED);
    }

    @Test
    void classify_captchaInBody_tempError() {
        String html = "<html>captcha required</html>";
        var outcome = HhApplyClient.classifyResponse("v1", 200, html, null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void classify_loginRedirectInBody_authError() {
        String html = "<html>войдите логин пароль account/login</html>";
        var outcome = HhApplyClient.classifyResponse("v1", 200, html, null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.AUTH_ERROR);
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void classify_jsonPermError_validation() {
        ObjectNode json = om.createObjectNode();
        json.put("error", "validationError");
        var outcome = HhApplyClient.classifyResponse("v1", 400, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.PERM_ERROR);
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void classify_captchaRequired_inJson() {
        ObjectNode json = om.createObjectNode();
        json.put("error", "captchaRequired");
        var outcome = HhApplyClient.classifyResponse("v1", 200, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }

    // ─── JSON body — errors array (HH.ru /applicant API format) ───────────────

    @Test
    void classify_alreadyApplied_inErrorsArray_returnsAlreadyApplied() {
        ObjectNode json = om.createObjectNode();
        ArrayNode errors = om.createArrayNode();
        ObjectNode err = om.createObjectNode();
        err.put("value", "alreadyApplied");
        err.put("type", "apply");
        errors.add(err);
        json.set("errors", errors);
        var outcome = HhApplyClient.classifyResponse("v1", 200, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.ALREADY_APPLIED);
    }

    @Test
    void classify_captchaRequired_inErrorsArray_returnsTempError() {
        // MN-4 fix: isCaptcha() handles array form
        ObjectNode json = om.createObjectNode();
        ArrayNode errors = om.createArrayNode();
        ObjectNode err = om.createObjectNode();
        err.put("value", "captchaRequired");
        errors.add(err);
        json.set("errors", errors);
        var outcome = HhApplyClient.classifyResponse("v1", 200, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }

    // ─── HTML fallback paths ───────────────────────────────────────────────────

    @Test
    void classify_htmlDoctype_tempError() {
        String html = "<!DOCTYPE html><html><body>Some page</body></html>";
        var outcome = HhApplyClient.classifyResponse("v1", 200, html, null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo("html_not_json");
    }

    @Test
    void classify_badGatewayHtml_tempError() {
        String html = "<html><body>502 bad gateway</body></html>";
        var outcome = HhApplyClient.classifyResponse("v1", 200, html, null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void classify_serviceUnavailableHtml_tempError() {
        String html = "<html>503 service unavailable</html>";
        var outcome = HhApplyClient.classifyResponse("v1", 200, html, null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void classify_antibot_inHtmlBody_tempError() {
        String html = "<html><body>Please complete antibot challenge</body></html>";
        var outcome = HhApplyClient.classifyResponse("v1", 200, html, null);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }

    // ─── Perm error codes ──────────────────────────────────────────────────────

    @Test
    void classify_invalidResumeErrorCode_permError() {
        ObjectNode json = om.createObjectNode();
        json.put("error", "invalidResume");
        var outcome = HhApplyClient.classifyResponse("v1", 400, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.PERM_ERROR);
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void classify_forbiddenErrorCode_permError() {
        ObjectNode json = om.createObjectNode();
        json.put("error", "forbiddenAction");
        var outcome = HhApplyClient.classifyResponse("v1", 400, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.PERM_ERROR);
        assertThat(outcome.retryable()).isFalse();
    }
}
