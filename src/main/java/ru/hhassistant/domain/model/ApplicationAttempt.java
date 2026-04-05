package ru.hhassistant.domain.model;

import java.time.Instant;

/**
 * Одна попытка отправить отклик на вакансию.
 * Используется для передачи контекста в VacancyApplyService и записи в лог/метрики.
 */
public record ApplicationAttempt(
    long chatId,
    String vacancyId,
    String vacancyTitle,
    String employer,
    String resumeId,
    String coverLetter,    // пустая строка = без письма
    int attemptNumber,
    Instant startedAt
) {}
