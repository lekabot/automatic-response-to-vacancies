package ru.hhassistant.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import okhttp3.OkHttpClient;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.policy.RetryPolicy;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * CDI Producers для инфраструктурных singleton-бинов.
 */
@ApplicationScoped
public class InfrastructureProducers {

    /**
     * Jackson ObjectMapper: поддержка Java 8 time, tolerant к неизвестным полям.
     */
    @Produces
    @ApplicationScoped
    ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }

    /**
     * OkHttpClient для hh.ru: разумные таймауты, без cookie jar (состояние в caller).
     */
    @Produces
    @ApplicationScoped
    OkHttpClient okHttpClient(HhConfig hhConfig) {
        return new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();
    }

    /**
     * Системные часы UTC. Инжектируется везде, где нужно время.
     * В тестах заменяется на Clock.fixed() через CDI override или конструктор.
     */
    @Produces
    @ApplicationScoped
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Политика retry/backoff: base=60s, maxExp=6, jitter=30s.
     * Stateless singleton, использует инжектированный Clock.
     */
    @Produces
    @ApplicationScoped
    RetryPolicy retryPolicy(Clock clock) {
        return RetryPolicy.defaultPolicy(clock);
    }
}
