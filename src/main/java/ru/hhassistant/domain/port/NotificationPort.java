package ru.hhassistant.domain.port;

import ru.hhassistant.domain.model.ReportSnapshot;

public interface NotificationPort {
  void sendHourlyReport(long chatId, ReportSnapshot snapshot);

  void sendFinalReport(long chatId, ReportSnapshot snapshot);

  void sendSessionInvalidWarning(long chatId);

  void sendHhTempUnavailableWarning(long chatId);

  void sendMessage(long chatId, String htmlText);
}
