package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.infrastructure.html.HhHtmlExtractor;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

  private static final Pattern XSRF_TOKEN_RE = Pattern.compile("\"xsrfToken\"\\s*:\\s*\"([a-zA-Z0-9_\\-]{16,64})\"");

  @Inject
  OkHttpClient httpClient;
  @Inject
  RateLimitedHttpExecutor httpExecutor;
  @Inject
  HhConfig hhConfig;
  @Inject
  ObjectMapper objectMapper;

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

    OkHttpClient attemptClient = buildAttemptClient(hhtoken, perAttemptTimeoutSec);
    String xsrf;
    try {
      xsrf = resolveXsrf(hhtoken, attemptClient);
    } catch (IOException ex) {
      log.warn("apply.xsrf_resolve_failed vacancyId={} error={}", vacancyId, ex.getMessage());
      return ApplyOutcome.authError("xsrf_resolve_failed");
    }
    if (xsrf == null) {
      log.warn("apply.no_xsrf vacancyId={}", vacancyId);
      return ApplyOutcome.authError("no_xsrf");
    }

    for (int attempt = 0; attempt < MAX_INNER_RETRIES; attempt++) {
      if (System.currentTimeMillis() > deadline) {
        log.warn("apply.total_timeout vacancyId={} attempt={}", vacancyId, attempt);
        return ApplyOutcome.timeout();
      }
      try {
        last = attemptOnce(vacancyId, resumeId, coverLetter, hhtoken, xsrf, attemptClient);
      } catch (SocketTimeoutException ex) {
        log.warn("apply.attempt_timeout vacancyId={} attempt={}", vacancyId, attempt);
        last = ApplyOutcome.timeout();
      } catch (IOException ex) {
        log.warn("apply.attempt_transport_error vacancyId={} attempt={} error={}",
          vacancyId, attempt, ex.getMessage());
        last = ApplyOutcome.tempError("transport_error", ex.getMessage());
      }

      if (!last.retryable() || last.status() == ApplyStatus.APPLIED
        || last.status() == ApplyStatus.ALREADY_APPLIED) {
        return last;
      }
      if (attempt < MAX_INNER_RETRIES - 1) {
        long backoffMs = (long) (Math.pow(1.5, attempt) * 1000 + Math.random() * 800);
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return last;
        }
      }
    }
    return last;
  }

  private ApplyOutcome attemptOnce(
    String vacancyId, String resumeId, String coverLetter, String hhtoken,
    String xsrf, OkHttpClient client
  ) throws IOException {
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

    log.debug("apply.request vacancyId={} resumeHash={}",
      vacancyId, resumeId.substring(0, Math.min(8, resumeId.length())));

    try (Response response = httpExecutor.execute(request)) {
      String bodyText = response.body() != null ? response.body().string() : "";
      JsonNode parsed = null;
      try {
        parsed = objectMapper.readTree(bodyText);
      } catch (Exception ignored) {
      }
      return classifyResponse(vacancyId, response.code(), bodyText, parsed);
    }
  }

  private String resolveXsrf(String hhtoken, OkHttpClient client) throws IOException {
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
      String fromDom = HhHtmlExtractor.extractXsrfFromHtml(html);
      if (fromDom != null) return fromDom;
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
    if (code == 429) {
      log.warn("apply.rate_limited vacancyId={}", vacancyId);
      return new ApplyOutcome(ApplyStatus.TEMP_ERROR, 429, "http_429", true);
    }
    if (code == 401 || code == 403) {
      return new ApplyOutcome(ApplyStatus.AUTH_ERROR, code, "http_auth", false);
    }
    if (code >= 500) {
      return new ApplyOutcome(ApplyStatus.TEMP_ERROR, code, "http_5xx", true);
    }

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

    log.warn("apply.unclassified_response vacancyId={} code={} snippet={}",
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
    if (err.isTextual()) return err.asText().toLowerCase().contains("captcha");
    if (err.isArray()) {
      for (JsonNode e : err) {
        if (e.path("value").asText("").toLowerCase().contains("captcha")) return true;
      }
    }
    return false;
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
