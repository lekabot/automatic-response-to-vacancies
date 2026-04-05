package ru.hhassistant.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.hhassistant.infrastructure.hh.ApplyStatus;
import ru.hhassistant.infrastructure.hh.HhApplyClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для статической функции classifyResponse.
 * Тестируется логика классификации без HTTP-запросов.
 */
class HhApplyClientClassifyTest {

    private final ObjectMapper om = new ObjectMapper();

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

    @Test
    void classify_jsonAlreadyApplied_returnsAlreadyApplied() throws Exception {
        ObjectNode json = om.createObjectNode();
        json.put("error", "alreadyApplied");
        var outcome = HhApplyClient.classifyResponse("v1", 200, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.ALREADY_APPLIED);
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void classify_jsonSuccess_returnsApplied() throws Exception {
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
    void classify_jsonPermError_validation() throws Exception {
        ObjectNode json = om.createObjectNode();
        json.put("error", "validationError");
        var outcome = HhApplyClient.classifyResponse("v1", 400, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.PERM_ERROR);
        assertThat(outcome.retryable()).isFalse();
    }

    @Test
    void classify_captchaRequired_inJson() throws Exception {
        ObjectNode json = om.createObjectNode();
        json.put("error", "captchaRequired");
        var outcome = HhApplyClient.classifyResponse("v1", 200, json.toString(), json);
        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.retryable()).isTrue();
    }
}
