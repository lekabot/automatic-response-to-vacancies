package ru.hhassistant.infrastructure.hh;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.hhassistant.config.HhConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RateLimitedHttpExecutor {
  @Inject
  OkHttpClient httpClient;
  @Inject
  HhConfig hhConfig;

  private final Object lock = new Object();
  private volatile double tokens;
  private volatile long lastRefillNanos = System.nanoTime();

  public Response execute(Request request) throws IOException {
    acquireToken();
    return httpClient.newCall(request).execute();
  }

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
