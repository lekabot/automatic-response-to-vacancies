package ru.hhassistant.infrastructure.hh;

import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hhassistant.config.HhConfig;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitedHttpExecutorTest {

    @Mock OkHttpClient httpClient;
    @Mock HhConfig hhConfig;
    @Mock HhConfig.RateLimitConfig rateLimitConfig;
    @Mock Call mockCall;

    private RateLimitedHttpExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new RateLimitedHttpExecutor();
        inject(executor, "httpClient", httpClient);
        inject(executor, "hhConfig", hhConfig);
        // High QPS + burst so execute() never blocks in tests
        lenient().when(hhConfig.rateLimit()).thenReturn(rateLimitConfig);
        lenient().when(rateLimitConfig.qps()).thenReturn(1000.0);
        lenient().when(rateLimitConfig.burst()).thenReturn(1000);
    }

    @Test
    void execute_delegatesToHttpClient() throws IOException {
        Request request = new Request.Builder().url("https://hh.ru/").build();
        Response response = buildResponse(200, request, "ok");

        when(httpClient.newCall(any())).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(response);

        Response result = executor.execute(request);

        assertThat(result.code()).isEqualTo(200);
        verify(httpClient).newCall(request);
    }

    @Test
    void execute_propagatesIOExceptionFromHttpClient() throws IOException {
        Request request = new Request.Builder().url("https://hh.ru/").build();
        when(httpClient.newCall(any())).thenReturn(mockCall);
        when(mockCall.execute()).thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> executor.execute(request))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Connection refused");
    }

    @Test
    void execute_multipleCallsWithHighQps_allSucceed() throws IOException {
        Request request = new Request.Builder().url("https://hh.ru/").build();
        Response response = buildResponse(200, request, "ok");

        when(httpClient.newCall(any())).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(response);

        // With burst=1000, 5 rapid calls should not be throttled
        for (int i = 0; i < 5; i++) {
            Response r = executor.execute(request);
            assertThat(r.code()).isEqualTo(200);
        }
        verify(httpClient, times(5)).newCall(request);
    }

    @Test
    void execute_tokensExhausted_refillsAfterDelay() throws IOException {
        // Set very low burst to force throttling scenario (but high qps so wait is tiny)
        when(rateLimitConfig.qps()).thenReturn(10000.0); // very fast refill
        when(rateLimitConfig.burst()).thenReturn(1);     // burst of 1

        // Exhaust the token
        inject_tokens(executor, 0.01); // nearly empty

        Request request = new Request.Builder().url("https://hh.ru/").build();
        Response response = buildResponse(200, request, "ok");
        when(httpClient.newCall(any())).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(response);

        // Should still succeed (wait is very short with high QPS)
        long start = System.currentTimeMillis();
        Response result = executor.execute(request);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.code()).isEqualTo(200);
        // With qps=10000, wait should be << 1 second
        assertThat(elapsed).isLessThan(1000);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static Response buildResponse(int code, Request request, String body) {
        return new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(ResponseBody.create(body, MediaType.get("text/plain")))
            .build();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void inject_tokens(RateLimitedHttpExecutor target, double value) {
        try {
            Field field = target.getClass().getDeclaredField("tokens");
            field.setAccessible(true);
            field.setDouble(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
