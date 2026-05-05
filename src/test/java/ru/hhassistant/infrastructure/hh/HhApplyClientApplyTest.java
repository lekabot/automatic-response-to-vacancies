package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.config.HhConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для HhApplyClient.apply() — XSRF-резолюция, attemptOnce(),
 * внутренний retry-loop. HTTP стаб через моки OkHttpClient / RateLimitedHttpExecutor.
 */
@ExtendWith(MockitoExtension.class)
class HhApplyClientApplyTest {

    @Mock OkHttpClient httpClient;
    @Mock OkHttpClient.Builder mockBuilder;
    @Mock OkHttpClient mockAttemptClient;
    @Mock Call mockXsrfCall;
    @Mock RateLimitedHttpExecutor httpExecutor;
    @Mock HhConfig hhConfig;

    private final ObjectMapper om = new ObjectMapper();
    private HhApplyClient applyClient;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(hhConfig.userAgent()).thenReturn("TestAgent/1.0");

        // Mock builder chain used in buildAttemptClient()
        lenient().when(httpClient.newBuilder()).thenReturn(mockBuilder);
        lenient().when(mockBuilder.connectTimeout(anyLong(), any())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.readTimeout(anyLong(), any())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.writeTimeout(anyLong(), any())).thenReturn(mockBuilder);
        lenient().when(mockBuilder.build()).thenReturn(mockAttemptClient);

        applyClient = new HhApplyClient();
        inject(applyClient, "httpClient", httpClient);
        inject(applyClient, "httpExecutor", httpExecutor);
        inject(applyClient, "hhConfig", hhConfig);
        inject(applyClient, "objectMapper", om);
    }

    // ─── resolveXsrf failures ─────────────────────────────────────────────────

    @Test
    void apply_xsrfIOException_returnsAuthError() throws IOException {
        when(mockAttemptClient.newCall(any())).thenReturn(mockXsrfCall);
        when(mockXsrfCall.execute()).thenThrow(new IOException("Connection refused"));

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.AUTH_ERROR);
        assertThat(outcome.errorCode()).isEqualTo("xsrf_resolve_failed");
        verifyNoInteractions(httpExecutor);
    }

    @Test
    void apply_noXsrfInResponse_returnsAuthError() throws IOException {
        stubXsrfResponse("<html><body>No token here</body></html>", List.of());

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.AUTH_ERROR);
        assertThat(outcome.errorCode()).isEqualTo("no_xsrf");
        verifyNoInteractions(httpExecutor);
    }

    // ─── XSRF extraction paths ────────────────────────────────────────────────

    @Test
    void apply_xsrfFromInputTag_successfulApply() throws IOException {
        stubXsrfResponse(
            "<html><body><input name=\"_xsrf\" value=\"xsrf-input-token\"></body></html>",
            List.of()
        );
        stubApplyResponse(200, successJson());

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.APPLIED);
    }

    @Test
    void apply_xsrfFromJsonScript_successfulApply() throws IOException {
        String html = "<html><script>{\"xsrfToken\": \"json-xsrf-abc12345678\"}</script></html>";
        stubXsrfResponse(html, List.of());
        stubApplyResponse(200, successJson());

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.APPLIED);
    }

    @Test
    void apply_xsrfFromSetCookie_successfulApply() throws IOException {
        stubXsrfResponse(
            "<html><body>no xsrf in body</body></html>",
            List.of("_xsrf=cookie-xsrf-value; Path=/; SameSite=Strict")
        );
        stubApplyResponse(200, successJson());

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.APPLIED);
    }

    // ─── attemptOnce outcomes — non-retryable (return immediately, no sleep) ─

    @Test
    void apply_permErrorOnFirstAttempt_returnsPermErrorNoRetry() throws IOException {
        stubXsrfResponse(xsrfHtml(), List.of());
        ObjectNode json = om.createObjectNode();
        json.put("error", "validationError");
        stubApplyResponse(400, json.toString());

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.PERM_ERROR);
        verify(httpExecutor, times(1)).execute(any());
    }

    @Test
    void apply_alreadyApplied_returnsAlreadyAppliedNoRetry() throws IOException {
        stubXsrfResponse(xsrfHtml(), List.of());
        ObjectNode json = om.createObjectNode();
        json.put("error", "alreadyApplied");
        stubApplyResponse(200, json.toString());

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.ALREADY_APPLIED);
        verify(httpExecutor, times(1)).execute(any());
    }

    @Test
    void apply_authError401_returnsAuthErrorNoRetry() throws IOException {
        stubXsrfResponse(xsrfHtml(), List.of());
        stubApplyResponse(401, "");

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.AUTH_ERROR);
        verify(httpExecutor, times(1)).execute(any());
    }

    // ─── attemptOnce — IOException (transport error, retryable) ──────────────

    @Test
    void apply_ioExceptionOnAttempt_returnsTempError() throws IOException {
        stubXsrfResponse(xsrfHtml(), List.of());
        when(httpExecutor.execute(any())).thenThrow(new IOException("Network reset"));

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.TEMP_ERROR);
        assertThat(outcome.errorCode()).contains("transport_error");
    }

    // ─── SocketTimeout → APPLIED on retry ────────────────────────────────────

    @Test
    void apply_socketTimeoutThenSuccess_returnsApplied() throws IOException {
        stubXsrfResponse(xsrfHtml(), List.of());
        Response successResp = buildApplyResponse(200, successJson());
        when(httpExecutor.execute(any()))
            .thenThrow(new SocketTimeoutException("Read timed out"))
            .thenReturn(successResp);

        var outcome = applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        assertThat(outcome.status()).isEqualTo(ApplyStatus.APPLIED);
        verify(httpExecutor, times(2)).execute(any());
    }

    // ─── Cover letter included in request body ────────────────────────────────

    @Test
    void apply_withCoverLetter_postsToApplyUrl() throws IOException {
        stubXsrfResponse(xsrfHtml(), List.of());
        stubApplyResponse(200, successJson());
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);

        applyClient.apply("v1", "resume-abc", "Dear hiring manager...", "hhtoken", 30, 10);

        verify(httpExecutor).execute(captor.capture());
        assertThat(captor.getValue().url().toString()).isEqualTo(HhApplyClient.APPLY_URL);
        assertThat(captor.getValue().method()).isEqualTo("POST");
    }

    @Test
    void apply_withoutCoverLetter_postsToApplyUrl() throws IOException {
        stubXsrfResponse(xsrfHtml(), List.of());
        stubApplyResponse(200, successJson());
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);

        applyClient.apply("v1", "resume-abc", null, "hhtoken", 30, 10);

        verify(httpExecutor).execute(captor.capture());
        assertThat(captor.getValue().url().toString()).isEqualTo(HhApplyClient.APPLY_URL);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void stubXsrfResponse(String html, List<String> setCookieHeaders) throws IOException {
        Response.Builder builder = new Response.Builder()
            .request(new Request.Builder().url("https://hh.ru/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(ResponseBody.create(html, MediaType.get("text/html; charset=utf-8")));
        for (String cookie : setCookieHeaders) {
            builder.addHeader("Set-Cookie", cookie);
        }
        when(mockAttemptClient.newCall(any())).thenReturn(mockXsrfCall);
        when(mockXsrfCall.execute()).thenReturn(builder.build());
    }

    private void stubApplyResponse(int code, String body) throws IOException {
        when(httpExecutor.execute(any())).thenReturn(buildApplyResponse(code, body));
    }

    private Response buildApplyResponse(int code, String body) {
        return new Response.Builder()
            .request(new Request.Builder().url(HhApplyClient.APPLY_URL).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(ResponseBody.create(body, MediaType.get("application/json")))
            .build();
    }

    private String successJson() {
        ObjectNode json = om.createObjectNode();
        json.put("success", true);
        return json.toString();
    }

    private String xsrfHtml() {
        return "<html><body><input name=\"_xsrf\" value=\"test-xsrf-token-abc\"></body></html>";
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
