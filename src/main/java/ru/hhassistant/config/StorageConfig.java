package ru.hhassistant.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Конфигурация хранилища и retention политики.
 */
@ConfigMapping(prefix = "storage")
public interface StorageConfig {

    /**
     * Сколько дней хранить записи vacancies_seen после seenAt.
     * По истечении записи удаляются и вакансия может быть обработана снова.
     */
    @WithName("retention-days")
    @WithDefault("30")
    int retentionDays();
}
