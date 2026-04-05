package ru.hhassistant.adapter.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.LinkPreviewOptions;
import com.pengrad.telegrambot.model.request.*;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.SendResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import ru.hhassistant.domain.model.ReportSnapshot;
import ru.hhassistant.domain.port.NotificationPort;

/**
 * Отправляет сообщения пользователю через Telegram Bot API.
 * Реализует {@link NotificationPort} — единственный выход для уведомлений
 * из application-слоя.
 *
 * <p>Ответственность только за форматирование и отправку сообщений.
 * Никакой бизнес-логики.
 */
@ApplicationScoped
public class TelegramOutboundClient implements NotificationPort {

    private static final Logger log = Logger.getLogger(TelegramOutboundClient.class);

    @Inject TelegramBot bot;
    @Inject TelegramMessageFormatter formatter;

    @Override
    public void sendHourlyReport(long chatId, ReportSnapshot snapshot) {
        String text = formatter.formatHourlyReport(snapshot);
        sendHtml(chatId, text);
        log.infof("telegram.hourly_report_sent chatId=%d applied=%d", chatId, snapshot.applied());
    }

    @Override
    public void sendFinalReport(long chatId, ReportSnapshot snapshot) {
        String text = formatter.formatFinalReport(snapshot);
        sendHtml(chatId, text);
        log.infof("telegram.final_report_sent chatId=%d applied=%d", chatId, snapshot.applied());
    }

    @Override
    public void sendSessionInvalidWarning(long chatId) {
        String text = """
            ⚠️ <b>Сессия hh.ru недействительна.</b>
            
            Откройте настройки через /start и войдите снова.""";
        sendHtml(chatId, text);
    }

    @Override
    public void sendHhTempUnavailableWarning(long chatId) {
        String text = "⏳ hh.ru временно недоступен. Следующая попытка — по расписанию.";
        sendHtml(chatId, text);
    }

    @Override
    public void sendMessage(long chatId, String htmlText) {
        sendHtml(chatId, htmlText);
    }

    /**
     * Отправляет HTML-сообщение, игнорирует ошибки отправки (логирует).
     */
    public void sendHtml(long chatId, String htmlText) {
        sendHtml(chatId, htmlText, null);
    }

    public void sendHtml(long chatId, String htmlText, InlineKeyboardMarkup keyboard) {
        try {
            SendMessage req = new SendMessage(chatId, htmlText)
                .parseMode(ParseMode.HTML)
                .linkPreviewOptions(new LinkPreviewOptions().isDisabled(true));
            if (keyboard != null) req = req.replyMarkup(keyboard);
            SendResponse resp = bot.execute(req);
            if (!resp.isOk()) {
                log.warnf("telegram.send_failed chatId=%d code=%d desc=%s",
                    chatId, resp.errorCode(), resp.description());
            }
        } catch (Exception ex) {
            log.errorf(ex, "telegram.send_exception chatId=%d", chatId);
        }
    }

    public boolean editMessage(long chatId, int messageId, String htmlText, InlineKeyboardMarkup keyboard) {
        try {
            EditMessageText req = new EditMessageText(chatId, messageId, htmlText)
                .parseMode(ParseMode.HTML)
                .linkPreviewOptions(new LinkPreviewOptions().isDisabled(true));
            if (keyboard != null) req = req.replyMarkup(keyboard);
            BaseResponse resp = bot.execute(req);
            return resp.isOk();
        } catch (Exception ex) {
            log.warnf(ex, "telegram.edit_failed chatId=%d messageId=%d", chatId, messageId);
            return false;
        }
    }
}
