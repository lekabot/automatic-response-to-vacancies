package ru.hhassistant.domain.port;

import ru.hhassistant.domain.model.ReportSnapshot;
import ru.hhassistant.domain.model.VacancyDecision;
import ru.hhassistant.domain.model.VacancyProcessingState;
import ru.hhassistant.domain.model.VacancyStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Порт доступа к хранилищу состояний вакансий.
 * Имплементируется инфраструктурным слоем через jOOQ + PostgreSQL.
 */
public interface VacancyRepository {

  /**
   * Атомарно пытается заклеймировать вакансию для обработки.
   * Логика claimability полностью внутри реализации; вызывающий код
   * только интерпретирует результат через pattern matching.
   */
  VacancyDecision tryClaim(
    long chatId,
    String vacancyId,
    String title,
    String employer,
    String url,
    String salaryText,
    int leaseMinutes,
    int retentionDays,
    Instant now
  );

  /**
   * Batch read-only preview: возвращает claim-path для набора vacancy_id без модификации БД.
   * Используется для быстрой проверки, есть ли в наборе actionable вакансии.
   */
  Map<String, ClaimPath> batchPeek(
    long chatId,
    List<String> vacancyIds,
    int leaseMinutes,
    int retentionDays,
    Instant now
  );

  void persistOutcome(
    long chatId,
    String vacancyId,
    VacancyStatus status,
    String lastError,           // null если без ошибки
    Instant nextRetryAt,        // null если retry не нужен
    Instant now
  );


  void upsertSkipped(
    long chatId,
    String vacancyId,
    String title,
    String employer,
    String url,
    String salaryText,
    VacancyStatus status,
    Instant now
  );

  /**
   * Считает количество APPLIED за последние 24 часа для пользователя.
   */
  int countAppliedToday(long chatId, Instant since);

  /**
   * Статистика сессии начиная с windowStart.
   */
  ReportSnapshot sessionStats(long chatId, Instant windowStart, Instant windowEnd, int dailyLimit);

  /**
   * Список вакансий REQUIRES_TEST в окне сессии (лимит по количеству).
   */
  List<ReportSnapshot.TestVacancyRef> requiresTestInWindow(long chatId, Instant windowStart, int limit);

  /**
   * Удаляет все vacancies_seen для пользователя. Возвращает кол-во удалённых строк.
   */
  int deleteAll(long chatId);

  Optional<VacancyProcessingState> findById(long chatId, String vacancyId);

  /**
   * Пути для batch peek (упрощённые, без полного VacancyDecision).
   */
  enum ClaimPath {
    /**
     * Вакансия находится в терминальном статусе.
     */
    TERMINAL,
    /**
     * Вакансия в backoff — nextRetryAt не наступил.
     */
    BACKOFF,
    /**
     * Вакансия обрабатывается (IN_PROGRESS + активная lease).
     */
    IN_PROGRESS,
    /**
     * Вакансия готова к обработке.
     */
    CLAIMABLE
  }
}
