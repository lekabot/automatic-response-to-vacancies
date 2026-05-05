package ru.hhassistant.infrastructure.hh;

import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.model.SessionValidationResult;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class HhSessionValidatorTest {

    @Mock OkHttpClient httpClient;
    @Mock HhConfig hhConfig;

    private HhSessionValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(hhConfig.userAgent()).thenReturn("TestAgent/1.0");
        validator = new HhSessionValidator();
        inject(validator, "httpClient", httpClient);
        inject(validator, "hhConfig", hhConfig);
    }

    // ─── null / blank hhtoken ─────────────────────────────────────────────────

    @Test
    void validate_nullToken_returnsInvalid() {
        assertThat(validator.validate(null)).isEqualTo(SessionValidationResult.INVALID);
    }

    @Test
    void validate_blankToken_returnsInvalid() {
        assertThat(validator.validate("   ")).isEqualTo(SessionValidationResult.INVALID);
    }

    // ─── HTTP status codes ─────────────────────────────────────────────────────

    @Test
    void validate_http401_returnsInvalid() throws IOException {
        stubHttpResponse(buildResponse(401, "https://hh.ru/applicant/resumes", ""));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.INVALID);
    }

    @Test
    void validate_http403_returnsInvalid() throws IOException {
        stubHttpResponse(buildResponse(403, "https://hh.ru/applicant/resumes", ""));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.INVALID);
    }

    @Test
    void validate_http429_returnsTempUnavailable() throws IOException {
        stubHttpResponse(buildResponse(429, "https://hh.ru/applicant/resumes", ""));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.TEMP_UNAVAILABLE);
    }

    @Test
    void validate_http500_returnsTempUnavailable() throws IOException {
        stubHttpResponse(buildResponse(500, "https://hh.ru/applicant/resumes", "Server Error"));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.TEMP_UNAVAILABLE);
    }

    @Test
    void validate_http503_returnsTempUnavailable() throws IOException {
        stubHttpResponse(buildResponse(503, "https://hh.ru/applicant/resumes", "Service Unavailable"));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.TEMP_UNAVAILABLE);
    }

    // ─── redirect to login ────────────────────────────────────────────────────

    @Test
    void validate_redirectedToLoginUrl_returnsInvalid() throws IOException {
        stubHttpResponse(buildResponse(200, "https://hh.ru/account/login?backurl=/applicant/resumes", "Login page"));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.INVALID);
    }

    // ─── session dead markers in body ─────────────────────────────────────────

    @Test
    void validate_sessionExpiredInBody_returnsInvalid() throws IOException {
        String body = "<html>сессия истекла. Пожалуйста <a href=\"/account/login\">войдите</a></html>";
        stubHttpResponse(buildResponse(200, "https://hh.ru/applicant/resumes", body));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.INVALID);
    }

    // ─── challenge / captcha in body ──────────────────────────────────────────

    @Test
    void validate_captchaInBody_returnsTempUnavailable() throws IOException {
        String body = "<html><body>Please solve captcha to continue</body></html>";
        stubHttpResponse(buildResponse(200, "https://hh.ru/applicant/resumes", body));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.TEMP_UNAVAILABLE);
    }

    @Test
    void validate_challengeInBody_returnsTempUnavailable() throws IOException {
        String body = "<html>antibot challenge required</html>";
        stubHttpResponse(buildResponse(200, "https://hh.ru/applicant/resumes", body));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.TEMP_UNAVAILABLE);
    }

    // ─── successful session ───────────────────────────────────────────────────

    @Test
    void validate_successfulResumePage_returnsValid() throws IOException {
        String body = "<html><body><h1>Мои резюме</h1></body></html>";
        stubHttpResponse(buildResponse(200, "https://hh.ru/applicant/resumes", body));
        assertThat(validator.validate("valid-hhtoken")).isEqualTo(SessionValidationResult.VALID);
    }

    // ─── network exceptions ───────────────────────────────────────────────────

    @Test
    void validate_socketTimeout_returnsTempUnavailable() throws IOException {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(mockCall);
        when(mockCall.execute()).thenThrow(new SocketTimeoutException("Read timed out"));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.TEMP_UNAVAILABLE);
    }

    @Test
    void validate_ioException_returnsTempUnavailable() throws IOException {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(mockCall);
        when(mockCall.execute()).thenThrow(new IOException("Network unreachable"));
        assertThat(validator.validate("some-token")).isEqualTo(SessionValidationResult.TEMP_UNAVAILABLE);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void stubHttpResponse(Response response) throws IOException {
        Call mockCall = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(response);
    }

    private static Response buildResponse(int code, String url, String body) {
        Request request = new Request.Builder().url(url).build();
        return new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(ResponseBody.create(body, MediaType.get("text/html; charset=utf-8")))
            .build();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
