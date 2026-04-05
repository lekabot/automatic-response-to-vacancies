package ru.hhassistant.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import ru.hhassistant.domain.port.VacancyRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * Управляет переходами состояний вакансий, не связанными напрямую с откликом.
 * Например: ручной сброс истории, диагностические операции.
 */
@ApplicationScoped
public class VacancyStateService {

    @Inject VacancyRepository vacancyRepository;
    @Inject Clock clock;

    /**
     * Считает количество откликов за последние 24 часа для пользователя.
     */
    public int countAppliedToday(long chatId) {
        Instant since = clock.instant().minusSeconds(24 * 3600);
        return vacancyRepository.countAppliedToday(chatId, since);
    }

    /**
     * Удаляет всю историю обработанных вакансий для пользователя.
     *
     * @return количество удалённых записей
     */
    public int resetHistory(long chatId) {
        return vacancyRepository.deleteAll(chatId);
    }
}
