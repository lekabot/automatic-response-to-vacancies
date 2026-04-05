package ru.hhassistant.infrastructure.hh;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jboss.logging.Logger;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.model.SessionValidationResult;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;

/**
 * Проверяет, является ли сессия hh.ru валидной.
 *
 * <p>Алгоритм:
 * <ol>
 *   <li>GET /applicant/resumes с cookie hhtoken.</li>
 *   <li>429, 5xx, transport error → TEMP_UNAVAILABLE.</li>
 *   <li>401, 403, redirect на account/login, маркеры истёкшей сессии → INVALID.</li>
 *   <li>200 с URL /applicant/ → VALID.</li>
 *   <li>Всё остальное → TEMP_UNAVAILABLE (не известно — не рубим токен).</li>
 * </ol>
 */
@ApplicationScoped
public class HhSessionValidator {

    private static final Logger log = Logger.getLogger(HhSessionValidator.class);
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

    @Inject OkHttpClient httpClient;
    @Inject HhConfig hhConfig;

    /**
     * Валидирует сессию по hhtoken cookie.
     *
     * @param hhtoken значение cookie hhtoken; null → немедленно INVALID
     */
    public SessionValidationResult validate(String hhtoken) {
        if (hhtoken == null || hhtoken.isBlank()) {
            log.warn("session.invalid reason=missing_hhtoken");
            return SessionValidationResult.INVALID;
        }

        Request request = new Request.Builder()
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
            log.warnf("session.temp_unavailable reason=timeout error=%s", ex.getMessage());
            return SessionValidationResult.TEMP_UNAVAILABLE;
        } catch (IOException ex) {
            log.warnf("session.temp_unavailable reason=transport error=%s", ex.getMessage());
            return SessionValidationResult.TEMP_UNAVAILABLE;
        }
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private SessionValidationResult classify(Response response) throws IOException {
        int code = response.code();
        String finalUrl = response.request().url().toString().toLowerCase();
        String rawBody = response.body() != null ? response.body().string() : "";
        // Ограничиваем размер для маркерного поиска; contentLength() не использовать после string()
        String bodyLow = (rawBody.length() > 25_000 ? rawBody.substring(0, 25_000) : rawBody)
            .toLowerCase();

        if (code == 429) {
            log.warn("session.temp_unavailable reason=http_429");
            return SessionValidationResult.TEMP_UNAVAILABLE;
        }
        if (code >= 500) {
            log.warnf("session.temp_unavailable reason=http_5xx code=%d", code);
            return SessionValidationResult.TEMP_UNAVAILABLE;
        }
        if (code == 401 || code == 403) {
            log.warnf("session.invalid reason=http_auth code=%d", code);
            return SessionValidationResult.INVALID;
        }
        if (finalUrl.contains("account/login")) {
            log.warn("session.invalid reason=login_redirect");
            return SessionValidationResult.INVALID;
        }
        boolean sessionDeadInBody = SESSION_DEAD_MARKERS.stream().anyMatch(bodyLow::contains)
            && bodyLow.contains("account/login");
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
        log.warnf("session.temp_unavailable reason=unexpected code=%d url=%s", code, finalUrl);
        return SessionValidationResult.TEMP_UNAVAILABLE;
    }
}
