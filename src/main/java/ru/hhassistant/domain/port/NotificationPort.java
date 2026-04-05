package ru.hhassistant.domain.port;

import ru.hhassistant.domain.model.ReportSnapshot;

/**
 * Порт отправки уведомлений пользователю.
 * Имплементируется Telegram-адаптером.
 * Application-слой не знает о Telegram — только об этом интерфейсе.
 */
public interface NotificationPort {

    void sendHourlyReport(long chatId, ReportSnapshot snapshot);

    void sendFinalReport(long chatId, ReportSnapshot snapshot);

    void sendSessionInvalidWarning(long chatId);

    void sendHhTempUnavailableWarning(long chatId);

    void sendMessage(long chatId, String htmlText);
}
