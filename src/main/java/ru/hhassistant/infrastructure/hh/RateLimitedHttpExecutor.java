package ru.hhassistant.infrastructure.hh;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.hhassistant.config.HhConfig;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Обёртка над OkHttpClient с token-bucket rate limiting.
 *
 * <p>Все HTTP-запросы к hh.ru должны проходить через этот класс.
 * Реализует QPS-лимит согласно {@link HhConfig.RateLimitConfig}.
 *
 * <p>Предназначен для работы на virtual thread — {@code acquire()} блокирует,
 * но это приемлемо на VT.
 */
@ApplicationScoped
public class RateLimitedHttpExecutor {

    private static final Logger log = Logger.getLogger(RateLimitedHttpExecutor.class);

    @Inject OkHttpClient httpClient;
    @Inject HhConfig hhConfig;

    private final Object lock = new Object();
    private volatile double tokens;
    private volatile long lastRefillNanos = System.nanoTime();

    /** Выполняет запрос с учётом rate limit. Блокирует до получения токена. */
    public Response execute(Request request) throws IOException {
        acquireToken();
        return httpClient.newCall(request).execute();
    }

    // ─── token bucket ─────────────────────────────────────────────────────────

    private void acquireToken() {
        double qps = hhConfig.rateLimit().qps();
        double burst = hhConfig.rateLimit().burst();

        synchronized (lock) {
            refill(qps, burst);
            if (tokens < 1.0) {
                long waitNanos = (long) ((1.0 - tokens) / qps * 1_000_000_000L);
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Rate limiter interrupted", e);
                }
                refill(qps, burst);
            }
            tokens = Math.max(0, tokens - 1.0);
        }
    }

    private void refill(double qps, double burst) {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(burst, tokens + elapsed * qps);
        lastRefillNanos = now;
    }
}
