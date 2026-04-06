package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import ru.hhassistant.config.HhConfig;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Выполняет отклик на вакансию через web-интерфейс hh.ru (не публичный API).
 *
 * <p>Endpoint: {@code POST https://hh.ru/applicant/vacancy_response/popup}
 *
 * <p>Требует:
 * <ol>
 *   <li>Cookie hhtoken (auth).</li>
 *   <li>X-XSRFToken (извлекается из cookie или HTML при необходимости).</li>
 * </ol>
 *
 * <p>Реализует внутренний retry (до 3 попыток) для сетевых/временных ошибок.
 * Retry-политика на уровне вакансии (межцикловый backoff) — в {@link ru.hhassistant.application.VacancyApplyService}.
 */
@ApplicationScoped
@Slf4j
public class HhApplyClient {
    static final String APPLY_URL = "https://hh.ru/applicant/vacancy_response/popup";
    static final String WEB_BASE = "https://hh.ru";
    private static final int MAX_INNER_RETRIES = 3;
    private static final int APPLY_BODY_SNIPPET = 400;

    private static final Set<String> CHALLENGE_MARKERS = Set.of(
        "captcha", "smartcaptcha", "hcaptcha",
        "пройдите проверку", "подтвердите, что вы не робот",
        "challenge", "antibot", "rate limit", "слишком много запросов"
    );

    private static final Pattern XSRF_TOKEN_RE =
        Pattern.compile("\"xsrfToken\"\\s*:\\s*\"([a-f0-9]{32})\"");

    @Inject OkHttpClient httpClient;
    @Inject RateLimitedHttpExecutor httpExecutor;
    @Inject HhConfig hhConfig;
    @Inject ObjectMapper objectMapper;

    /**
     * Откликается на вакансию.
     *
     * @param vacancyId              ID вакансии hh.ru
     * @param resumeId               ID резюме (hash)
     * @param coverLetter            текст письма, пустая строка = без письма
     * @param hhtoken                cookie auth token
     * @param totalTimeoutSeconds    общий таймаут цикла retries
     * @param perAttemptTimeoutSec   таймаут одной попытки
     */
    public ApplyOutcome apply(
        String vacancyId,
        String resumeId,
        String coverLetter,
        String hhtoken,
        int totalTimeoutSeconds,
        int perAttemptTimeoutSec
    ) {
        long deadline = System.currentTimeMillis() + totalTimeoutSeconds * 1000L;
        ApplyOutcome last = ApplyOutcome.permError("no_attempts");

        for (int attempt = 0; attempt < MAX_INNER_RETRIES; attempt++) {
            if (System.currentTimeMillis() > deadline) {
                log.warnf("apply.total_timeout vacancyId=%s attempt=%d", vacancyId, attempt);
                return ApplyOutcome.timeout();
            }
            try {
                OkHttpClient attemptClient = buildAttemptClient(hhtoken, perAttemptTimeoutSec);
                last = attemptOnce(vacancyId, resumeId, coverLetter, hhtoken, attemptClient);
            } catch (SocketTimeoutException ex) {
                log.warnf("apply.attempt_timeout vacancyId=%s attempt=%d", vacancyId, attempt);
                last = ApplyOutcome.timeout();
            } catch (IOException ex) {
                log.warnf("apply.attempt_transport_error vacancyId=%s attempt=%d error=%s",
                    vacancyId, attempt, ex.getMessage());
                last = ApplyOutcome.tempError("transport_error", ex.getMessage());
            }

            if (!last.retryable()) return last;
            if (last.status() == ApplyStatus.APPLIED || last.status() == ApplyStatus.ALREADY_APPLIED) {
                return last;
            }
            if (attempt < MAX_INNER_RETRIES - 1) {
                long backoffMs = (long) (Math.pow(1.5, attempt) * 1000 + Math.random() * 800);
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
        }
        return last;
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private ApplyOutcome attemptOnce(
        String vacancyId, String resumeId, String coverLetter, String hhtoken, OkHttpClient client
    ) throws IOException {
        String xsrf = resolveXsrf(hhtoken, client);
        if (xsrf == null) {
            log.warnf("apply.no_xsrf vacancyId=%s", vacancyId);
            return ApplyOutcome.authError("no_xsrf");
        }

        FormBody.Builder formBuilder = new FormBody.Builder()
            .add("vacancy_id", vacancyId)
            .add("resume_hash", resumeId.split("\\?")[0])
            .add("ignore_postponed", "true")
            .add("lux", "true");
        if (coverLetter != null && !coverLetter.isBlank()) {
            formBuilder.add("letter", coverLetter);
        }

        Request request = new Request.Builder()
            .url(APPLY_URL)
            .header("User-Agent", hhConfig.userAgent())
            .header("Cookie", "hhtoken=" + hhtoken)
            .header("Referer", WEB_BASE + "/vacancy/" + vacancyId)
            .header("X-XSRFToken", xsrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(formBuilder.build())
            .build();

        log.debugf("apply.request vacancyId=%s resumeHash=%s",
            vacancyId, resumeId.substring(0, Math.min(8, resumeId.length())));

        try (Response response = httpExecutor.execute(request)) {
            String bodyText = response.body() != null ? response.body().string() : "";
            JsonNode parsed = null;
            try { parsed = objectMapper.readTree(bodyText); } catch (Exception ignored) {}
            return classifyResponse(vacancyId, response.code(), bodyText, parsed);
        }
    }

    private String resolveXsrf(String hhtoken, OkHttpClient client) throws IOException {
        // Пробуем сначала из cookies
        // В нашей архитектуре каждый запрос stateless — XSRF получаем из корневой страницы
        Request homeReq = new Request.Builder()
            .url(WEB_BASE + "/")
            .header("Cookie", "hhtoken=" + hhtoken)
            .header("User-Agent", hhConfig.userAgent())
            .get()
            .build();

        try (Response resp = client.newCall(homeReq).execute()) {
            String html = resp.body() != null ? resp.body().string() : "";
            var m = XSRF_TOKEN_RE.matcher(html);
            if (m.find()) return m.group(1);
            // Fallback: из Set-Cookie заголовков
            for (String cookie : resp.headers("Set-Cookie")) {
                if (cookie.startsWith("_xsrf=")) {
                    return cookie.substring(6).split(";")[0];
                }
            }
        }
        return null;
    }

    public static ApplyOutcome classifyResponse(
        String vacancyId, int code, String bodyText, JsonNode parsed
    ) {
        // 429 → rate limit
        if (code == 429) {
            log.warnf("apply.rate_limited vacancyId=%s", vacancyId);
            return new ApplyOutcome(ApplyStatus.TEMP_ERROR, 429, "http_429", true);
        }
        // 401/403 → auth
        if (code == 401 || code == 403) {
            return new ApplyOutcome(ApplyStatus.AUTH_ERROR, code, "http_auth", false);
        }
        // 5xx → temp
        if (code >= 500) {
            return new ApplyOutcome(ApplyStatus.TEMP_ERROR, code, "http_5xx", true);
        }

        // Проверяем JSON body
        if (parsed != null && !parsed.isMissingNode()) {
            JsonNode err = parsed.path("error");
            if (err.isMissingNode()) err = parsed.path("errors");

            if (isAlreadyApplied(err)) return ApplyOutcome.alreadyApplied();
            if (isCaptcha(err)) return new ApplyOutcome(ApplyStatus.TEMP_ERROR, code, "captcha_required", true);

            String errStr = err.isTextual() ? err.asText() : null;
            if (errStr != null && isPermErrorCode(errStr)) {
                return new ApplyOutcome(ApplyStatus.PERM_ERROR, code, errStr, false);
            }
            if (code == 200) {
                JsonNode success = parsed.path("success");
                if (success.asBoolean(false) || "true".equalsIgnoreCase(success.asText())) {
                    return ApplyOutcome.applied();
                }
            }
        }

        // HTML-тело вместо JSON
        String bodyLow = bodyText.substring(0, Math.min(30000, bodyText.length())).toLowerCase();
        if (CHALLENGE_MARKERS.stream().anyMatch(bodyLow::contains)) {
            return new ApplyOutcome(ApplyStatus.TEMP_ERROR, code, "challenge_or_captcha", true);
        }
        if (bodyLow.contains("account/login") &&
            (bodyLow.contains("войдите") || bodyLow.contains("password") || bodyLow.contains("логин"))) {
            return ApplyOutcome.authError("session_expired_html");
        }
        if (code >= 500 || bodyLow.contains("502 bad gateway") || bodyLow.contains("503 service")) {
            return new ApplyOutcome(ApplyStatus.TEMP_ERROR, code, "server_error_html", true);
        }
        if (bodyLow.startsWith("<!") || bodyText.strip().startsWith("<html")) {
            return new ApplyOutcome(ApplyStatus.TEMP_ERROR, code, "html_not_json", true);
        }

        log.warnf("apply.unclassified_response vacancyId=%s code=%d snippet=%s",
            vacancyId, code, bodyText.substring(0, Math.min(APPLY_BODY_SNIPPET, bodyText.length())));
        return new ApplyOutcome(ApplyStatus.TEMP_ERROR, code, "unclassified_response", true);
    }

    private static boolean isAlreadyApplied(JsonNode err) {
        if (err.isTextual() && "alreadyApplied".equals(err.asText())) return true;
        if (err.isArray()) {
            for (JsonNode e : err) {
                if ("alreadyApplied".equals(e.path("value").asText())) return true;
            }
        }
        return false;
    }

    private static boolean isCaptcha(JsonNode err) {
        if (!err.isTextual()) return false;
        String s = err.asText().toLowerCase();
        return s.contains("captcha");
    }

    private static boolean isPermErrorCode(String code) {
        String low = code.toLowerCase();
        return low.contains("validation") || low.contains("invalid")
            || low.contains("forbidden") || low.contains("xsrf")
            || low.contains("auth") || low.contains("session")
            || low.contains("resume") || low.contains("letter")
            || "badrequest".equals(low);
    }

    private OkHttpClient buildAttemptClient(String hhtoken, int perAttemptTimeoutSec) {
        return httpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(perAttemptTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    }
}
