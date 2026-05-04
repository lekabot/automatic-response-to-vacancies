package ru.hhassistant.adapter.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.LinkPreviewOptions;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.SendResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.domain.model.ReportSnapshot;
import ru.hhassistant.domain.port.NotificationPort;

@Slf4j
@ApplicationScoped
public class TelegramOutboundClient implements NotificationPort {
  @Inject
  TelegramBot bot;
  @Inject
  TelegramMessageFormatter formatter;

  @Override
  public void sendHourlyReport(long chatId, ReportSnapshot snapshot) {
    var text = formatter.formatHourlyReport(snapshot);
    sendHtml(chatId, text);
    log.info("telegram.hourly_report_sent chatId={} applied={}", chatId, snapshot.applied());
  }

  @Override
  public void sendFinalReport(long chatId, ReportSnapshot snapshot) {
    String text = formatter.formatFinalReport(snapshot);
    sendHtml(chatId, text);
    log.info("telegram.final_report_sent chatId={} applied={}", chatId, snapshot.applied());
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
        log.warn("telegram.send_failed chatId={} code={} desc={}", chatId, resp.errorCode(), resp.description());
      }
    } catch (Exception ex) {
      log.error("telegram.send_exception chatId={}}", chatId);
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
      log.warn("telegram.edit_failed chatId={} messageId={}", chatId, messageId);
      return false;
    }
  }
}
