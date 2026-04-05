package ru.hhassistant.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Типизированная конфигурация Telegram-бота.
 * Токен читается из env: {@code TELEGRAM_BOT_TOKEN}.
 */
@ConfigMapping(prefix = "telegram")
public interface TelegramConfig {

    /** Токен бота. Обязателен. Берётся из env TELEGRAM_BOT_TOKEN. */
    @WithName("bot-token")
    String botToken();

    /** Таймаут long-polling запроса (секунды). */
    @WithName("long-poll-timeout-seconds")
    @WithDefault("30")
    int longPollTimeoutSeconds();

    /** Максимальное количество updates за один long-poll запрос. */
    @WithName("updates-limit")
    @WithDefault("100")
    int updatesLimit();

    /** Таймаут соединения OkHttpClient для бота (секунды). */
    @WithName("connect-timeout-seconds")
    @WithDefault("15")
    int connectTimeoutSeconds();

    /** Таймаут чтения для бота (секунды). */
    @WithName("read-timeout-seconds")
    @WithDefault("65")
    int readTimeoutSeconds();
}
