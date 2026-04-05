package ru.hhassistant.adapter.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import okhttp3.OkHttpClient;
import org.jboss.logging.Logger;
import ru.hhassistant.config.TelegramConfig;

import java.util.concurrent.TimeUnit;

/**
 * Адаптер Telegram Bot API.
 *
 * <p>Поднимает long-polling loop при старте приложения и останавливает при shutdown.
 * Единственная ответственность: принять update и передать в {@link TelegramCommandHandler}.
 *
 * <p>Telegram — только входящий/исходящий транспорт. Никакой бизнес-логики здесь нет.
 */
@ApplicationScoped
public class TelegramBotAdapter {

    private static final Logger log = Logger.getLogger(TelegramBotAdapter.class);

    @Inject TelegramConfig telegramConfig;
    @Inject TelegramCommandHandler commandHandler;

    private volatile TelegramBot bot;

    void onStart(@Observes StartupEvent event) {
        OkHttpClient botHttpClient = new OkHttpClient.Builder()
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
                    log.errorf(ex, "telegram.update_processing_error updateId=%d", update.updateId());
                }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        }, ex -> log.errorf(ex, "telegram.updates_listener_error"));

        log.info("Telegram long-polling started");
    }

    void onStop(@Observes ShutdownEvent event) {
        if (bot != null) {
            bot.removeGetUpdatesListener();
            log.info("Telegram long-polling stopped");
        }
    }

    /** Возвращает экземпляр бота для CDI-инъекции в {@link TelegramOutboundClient}. */
    @jakarta.enterprise.inject.Produces
    @ApplicationScoped
    TelegramBot telegramBot() {
        // bot создаётся в onStart; до этого момента вызов невозможен в normal scope
        if (bot == null) throw new IllegalStateException("TelegramBot not yet initialized");
        return bot;
    }
}
