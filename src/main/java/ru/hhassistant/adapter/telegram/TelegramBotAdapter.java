package ru.hhassistant.adapter.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import ru.hhassistant.config.TelegramConfig;

import java.util.concurrent.TimeUnit;

@Slf4j
@ApplicationScoped
public class TelegramBotAdapter {
  @Inject
  TelegramConfig telegramConfig;
  @Inject
  TelegramCommandHandler commandHandler;

  private volatile TelegramBot bot;

  void onStart(@Observes StartupEvent event) {
    var botHttpClient = new OkHttpClient.Builder()
      .connectTimeout(telegramConfig.connectTimeoutSeconds(), TimeUnit.SECONDS)
      .readTimeout(telegramConfig.readTimeoutSeconds(), TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .build();

    bot = new TelegramBot.Builder(telegramConfig.botToken())
      .okHttpClient(botHttpClient)
      .build();

    bot.setUpdatesListener(updates -> {
      for (var update : updates) {
        try {
          commandHandler.handleUpdate(update);
        } catch (Exception ex) {
          log.error("telegram.update_processing_error updateId={}", update.updateId());
        }
      }
      return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }, ex -> log.error("telegram.updates_listener_error"));

    log.info("Telegram long-polling started");
  }

  void onStop(@Observes ShutdownEvent event) {
    if (bot != null) {
      bot.removeGetUpdatesListener();
      log.info("Telegram long-polling stopped");
    }
  }

  @Produces
  @ApplicationScoped
  TelegramBot telegramBot() {
    if (bot == null) throw new IllegalStateException("TelegramBot not yet initialized");
    return bot;
  }
}
