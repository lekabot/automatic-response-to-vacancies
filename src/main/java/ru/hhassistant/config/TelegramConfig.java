package ru.hhassistant.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "telegram")
public interface TelegramConfig {
  String botToken();

  @WithDefault("30")
  int longPollTimeoutSeconds();

  @WithDefault("100")
  int updatesLimit();

  @WithDefault("15")
  int connectTimeoutSeconds();

  @WithDefault("65")
  int readTimeoutSeconds();
}
