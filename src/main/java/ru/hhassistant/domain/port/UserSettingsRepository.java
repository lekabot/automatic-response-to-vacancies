package ru.hhassistant.domain.port;

import java.util.Optional;

/**
 * Порт доступа к пользовательским настройкам.
 */
public interface UserSettingsRepository {

    Optional<UserSettingsRow> findByChatId(long chatId);

    void save(UserSettingsRow row);

    void updateKeywords(long chatId, java.util.List<String> keywords);

    void updateCoverLetter(long chatId, String coverLetter);

    void updateAuth(long chatId, String email, String hhtoken);

    void updateResume(long chatId, String resumeId, String resumeTitle);

    /**
     * Raw row для записи — отдельно от доменного UserSearchConfig, который включает
     * значения из глобального конфига.
     */
    record UserSettingsRow(
        long chatId,
        String email,
        String hhtoken,
        java.util.List<String> keywords,
        String coverLetter,
        String resumeId,
        String resumeTitle
    ) {
        public String coverLetterTemplate() { return coverLetter; }
    }
}
