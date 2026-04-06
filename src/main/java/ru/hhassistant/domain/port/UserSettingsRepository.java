package ru.hhassistant.domain.port;

import java.util.List;
import java.util.Optional;

public interface UserSettingsRepository {

  Optional<UserSettingsRow> findByChatId(long chatId);

  void save(UserSettingsRow row);

  void updateKeywords(long chatId, List<String> keywords);

  void updateCoverLetter(long chatId, String coverLetter);

  void updateAuth(long chatId, String email, String hhtoken);

  void updateResume(long chatId, String resumeId, String resumeTitle);

  record UserSettingsRow(
    long chatId,
    String email,
    String hhtoken,
    List<String> keywords,
    String coverLetter,
    String resumeId,
    String resumeTitle
  ) {
    public String coverLetterTemplate() {
      return coverLetter;
    }
  }
}
