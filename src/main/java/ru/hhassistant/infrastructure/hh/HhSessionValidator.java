package ru.hhassistant.infrastructure.hh;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.model.SessionValidationResult;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;

@Slf4j
@ApplicationScoped
public class HhSessionValidator {
  @Inject
  OkHttpClient httpClient;
  @Inject
  HhConfig hhConfig;

  private static final String RESUMES_URL = "https://hh.ru/applicant/resumes";

  private static final Set<String> SESSION_DEAD_MARKERS = Set.of(
    "сессия истекла", "session has expired", "session expired",
    "необходимо войти", "authorization required"
  );

  private static final Set<String> CHALLENGE_MARKERS = Set.of(
    "captcha", "smartcaptcha", "hcaptcha",
    "пройдите проверку", "подтвердите, что вы не робот",
    "challenge", "antibot", "rate limit", "слишком много запросов"
  );

  public SessionValidationResult validate(String hhtoken) {
    if (hhtoken == null || hhtoken.isBlank()) {
      log.warn("session.invalid reason=missing_hhtoken");
      return SessionValidationResult.INVALID;
    }

    var request = new Request.Builder()
      .url(RESUMES_URL)
      .header("User-Agent", hhConfig.userAgent())
      .header("Cookie", "hhtoken=" + hhtoken)
      .header("Accept", "text/html,application/xhtml+xml")
      .header("Accept-Language", "ru-RU,ru;q=0.9")
      .get()
      .build();

    try (Response response = httpClient.newCall(request).execute()) {
      return classify(response);
    } catch (SocketTimeoutException ex) {
      log.warn("session.temp_unavailable reason=timeout error={}", ex.getMessage());
      return SessionValidationResult.TEMP_UNAVAILABLE;
    } catch (IOException ex) {
      log.warn("session.temp_unavailable reason=transport error={}", ex.getMessage());
      return SessionValidationResult.TEMP_UNAVAILABLE;
    }
  }


  private SessionValidationResult classify(Response response) throws IOException {
    int code = response.code();
    var finalUrl = response.request().url().toString().toLowerCase();
    var rawBody = response.body() != null ? response.body().string() : "";
    var bodyLow = (rawBody.length() > 25_000 ? rawBody.substring(0, 25_000) : rawBody).toLowerCase();

    if (code == 429) {
      log.warn("session.temp_unavailable reason=http_429");
      return SessionValidationResult.TEMP_UNAVAILABLE;
    }
    if (code >= 500) {
      log.warn("session.temp_unavailable reason=http_5xx code={}", code);
      return SessionValidationResult.TEMP_UNAVAILABLE;
    }
    if (code == 401 || code == 403) {
      log.warn("session.invalid reason=http_auth code={}", code);
      return SessionValidationResult.INVALID;
    }
    if (finalUrl.contains("account/login")) {
      log.warn("session.invalid reason=login_redirect");
      return SessionValidationResult.INVALID;
    }
    var sessionDeadInBody = SESSION_DEAD_MARKERS.stream().anyMatch(bodyLow::contains) && bodyLow.contains("account/login");
    if (sessionDeadInBody) {
      log.warn("session.invalid reason=session_expired_marker");
      return SessionValidationResult.INVALID;
    }
    if (CHALLENGE_MARKERS.stream().anyMatch(bodyLow::contains)) {
      log.warn("session.temp_unavailable reason=challenge_or_antibot");
      return SessionValidationResult.TEMP_UNAVAILABLE;
    }
    if (code == 200 && finalUrl.contains("/applicant/")) {
      return SessionValidationResult.VALID;
    }
    log.warn("session.temp_unavailable reason=unexpected code={} url={}", code, finalUrl);
    return SessionValidationResult.TEMP_UNAVAILABLE;
  }
}
