package ru.hhassistant.infrastructure.hh;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.hhassistant.config.HhConfig;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class RateLimitedHttpExecutor {
  @Inject
  OkHttpClient httpClient;
  @Inject
  HhConfig hhConfig;

  private final ReentrantLock lock = new ReentrantLock();
  private volatile double tokens;
  private volatile long lastRefillNanos = System.nanoTime();

  public Response execute(Request request) throws IOException {
    acquireToken();
    return httpClient.newCall(request).execute();
  }

  private void acquireToken() {
    double qps = hhConfig.rateLimit().qps();
    double burst = hhConfig.rateLimit().burst();
    long waitNanos = 0;

    lock.lock();
    try {
      refill(qps, burst);
      if (tokens < 1.0) {
        waitNanos = (long) ((1.0 - tokens) / qps * 1_000_000_000L);
      }
      tokens = Math.max(0.0, tokens - 1.0);
    } finally {
      lock.unlock();
    }

    if (waitNanos > 0) {
      // Sleep вне lock — виртуальные потоки паркуются без пиннинга carrier thread
      LockSupport.parkNanos(waitNanos);
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Rate limiter interrupted");
      }
      lock.lock();
      try {
        refill(qps, burst);
      } finally {
        lock.unlock();
      }
    }
  }

  private void refill(double qps, double burst) {
    long now = System.nanoTime();
    double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
    tokens = Math.min(burst, tokens + elapsed * qps);
    lastRefillNanos = now;
  }
}
