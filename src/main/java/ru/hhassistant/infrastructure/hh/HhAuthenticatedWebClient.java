package ru.hhassistant.infrastructure.hh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import okhttp3.*;
import org.jboss.logging.Logger;
import ru.hhassistant.config.HhConfig;
import ru.hhassistant.domain.port.UserSettingsRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Клиент для аутентифицированных web-потоков hh.ru:
 * инициация входа, подтверждение OTP/пароля, извлечение резюме.
 *
 * <p>Используется только при настройке пользователя через Telegram-бот.
 * Не задействован в polling-цикле (там нужен только hhtoken + HhApplyClient).
 *
 * <p>Stateless: каждый вызов создаёт независимый HTTP-сеанс.
 */
@ApplicationScoped
public class HhAuthenticatedWebClient {

    private static final Logger log = Logger.getLogger(HhAuthenticatedWebClient.class);
    static final String WEB_BASE = "https://hh.ru";
    private static final String LOGIN_URL = WEB_BASE + "/account/login?role=applicant&backurl=%2F";

    private static final Pattern XSRF_TOKEN_RE =
        Pattern.compile("\"xsrfToken\"\\s*:\\s*\"([a-f0-9]{32})\"");
    private static final Pattern RESUME_ID_RE =
        Pattern.compile("/resume/([a-zA-Z0-9]+)");

    @Inject OkHttpClient httpClient;
    @Inject HhConfig hhConfig;
    @Inject ObjectMapper objectMapper;

    /**
     * Шаг 1: Инициирует вход по email — определяет, нужен ли OTP или пароль.
     *
     * @return {@link LoginInitResult} с методом и временными данными
     */
    public LoginInitResult initiateLogin(String email) {
        try (var session = new CookieSession(httpClient, hhConfig.userAgent())) {
            // Получаем стартовую страницу для установки cookies
            session.get(WEB_BASE + "/");
            String loginHtml = session.getHtml(LOGIN_URL);
            String xsrf = session.extractXsrf(loginHtml);
            if (xsrf == null) {
                return LoginInitResult.error("Не удалось получить XSRF-токен");
            }

            RequestBody otpBody = new FormBody.Builder()
                .add("login", email)
                .add("backurl", "/")
                .add("operationType", "applicant_otp_auth")
                .add("role", "applicant")
                .add("formatPhone", "true")
                .add("_xsrf", xsrf)
                .build();

            String otpJson = session.postJson(WEB_BASE + "/account/otp_generate",
                otpBody, xsrf, LOGIN_URL);
            JsonNode data = objectMapper.readTree(otpJson);
            String key = data.path("key").asText("");

            return switch (key) {
                case "PASSWORD_REQUIRED" -> LoginInitResult.passwordRequired(
                    session.getCookies(), xsrf,
                    data.path("redirectURL").asText(LOGIN_URL));
                case "CODE_SEND_OK", "OTP_SEND_OK" ->
                    LoginInitResult.otpRequired(session.getCookies(), xsrf, false);
                case "CODE_SEND_BLOCKED" ->
                    LoginInitResult.otpRequired(session.getCookies(), xsrf, true);
                default -> {
                    log.warnf("hh.login.unknown_key key=%s", key);
                    yield LoginInitResult.error("Неожиданный ответ от hh.ru: key=" + key);
                }
            };
        } catch (Exception ex) {
            log.errorf(ex, "hh.login.initiate_error email=%s", email.substring(0, 3) + "***");
            return LoginInitResult.error(ex.getMessage());
        }
    }

    /**
     * Шаг 2a: Завершает вход по паролю.
     */
    public LoginResult completePasswordLogin(String email, String password, LoginInitResult init) {
        try (var session = new CookieSession(httpClient, hhConfig.userAgent())) {
            session.restoreCookies(init.cookies());
            String xsrf = init.xsrf();

            RequestBody form = new FormBody.Builder()
                .add("username", email)
                .add("password", password)
                .add("_xsrf", xsrf)
                .add("remember", "true")
                .add("accountType", "APPLICANT")
                .add("isApplicantSignup", "false")
                .add("backurl", "/")
                .build();

            session.postJson(WEB_BASE + "/account/login", form, xsrf, init.redirectUrl());
            String hhtoken = session.getCookies().get("hhtoken");
            if (hhtoken == null) return LoginResult.failed("Не удалось получить hhtoken после входа по паролю");
            return LoginResult.success(hhtoken);
        } catch (Exception ex) {
            return LoginResult.failed(ex.getMessage());
        }
    }

    /**
     * Шаг 2b: Завершает вход по OTP-коду.
     */
    public LoginResult completeOtpLogin(String email, String code, LoginInitResult init) {
        try (var session = new CookieSession(httpClient, hhConfig.userAgent())) {
            session.restoreCookies(init.cookies());
            String xsrf = init.xsrf();

            RequestBody form = new FormBody.Builder()
                .add("username", email)
                .add("code", code.strip())
                .add("remember", "true")
                .add("accountType", "APPLICANT")
                .add("isApplicantSignup", "false")
                .add("operationType", "otp_auth")
                .add("backurl", "/")
                .add("_xsrf", xsrf)
                .build();

            session.postJson(WEB_BASE + "/account/login/by_code", form, xsrf, LOGIN_URL);
            String hhtoken = session.getCookies().get("hhtoken");
            if (hhtoken == null) return LoginResult.failed("Неверный код или не удалось получить hhtoken");
            return LoginResult.success(hhtoken);
        } catch (Exception ex) {
            return LoginResult.failed(ex.getMessage());
        }
    }

    /**
     * Получает список резюме пользователя.
     */
    public List<UserSettingsRepository.UserSettingsRow> getResumes(String hhtoken) {
        List<UserSettingsRepository.UserSettingsRow> result = new ArrayList<>();
        try (var session = new CookieSession(httpClient, hhConfig.userAgent())) {
            session.setCookie("hhtoken", hhtoken);
            String html = session.getHtml(WEB_BASE + "/applicant/resumes");
            result = parseResumesFromHtml(html);
        } catch (Exception ex) {
            log.warnf("hh.get_resumes.error error=%s", ex.getMessage());
        }
        return result;
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    private List<UserSettingsRepository.UserSettingsRow> parseResumesFromHtml(String html) {
        // Делегируем jsoup-парсеру
        return ru.hhassistant.infrastructure.html.HhHtmlExtractor.extractResumes(html);
    }

    // ─── inner types ──────────────────────────────────────────────────────────

    public record LoginInitResult(
        Method method,
        java.util.Map<String, String> cookies,
        String xsrf,
        String redirectUrl,
        boolean alreadySent,
        String errorMessage
    ) {
        public enum Method { OTP, PASSWORD, ERROR }

        public boolean isError() { return method == Method.ERROR; }

        static LoginInitResult otpRequired(java.util.Map<String, String> cookies, String xsrf, boolean alreadySent) {
            return new LoginInitResult(Method.OTP, cookies, xsrf, LOGIN_URL, alreadySent, null);
        }
        static LoginInitResult passwordRequired(java.util.Map<String, String> cookies, String xsrf, String redirectUrl) {
            return new LoginInitResult(Method.PASSWORD, cookies, xsrf, redirectUrl, false, null);
        }
        static LoginInitResult error(String message) {
            return new LoginInitResult(Method.ERROR, java.util.Map.of(), null, null, false, message);
        }
    }

    public record LoginResult(boolean success, String hhtoken, String errorMessage) {
        static LoginResult success(String token) { return new LoginResult(true, token, null); }
        static LoginResult failed(String msg) { return new LoginResult(false, null, msg); }
    }

    /**
     * Внутренний helper для управления cookie-сессией в рамках одного flow.
     * AutoCloseable — закрывает OkHttpClient-сессию.
     */
    private static final class CookieSession implements AutoCloseable {
        private final java.util.Map<String, String> cookies = new java.util.LinkedHashMap<>();
        private final OkHttpClient client;
        private final String userAgent;

        CookieSession(OkHttpClient base, String userAgent) {
            this.userAgent = userAgent;
            this.client = base.newBuilder()
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> newCookies) {
                        for (Cookie c : newCookies) cookies.put(c.name(), c.value());
                    }
                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        return cookies.entrySet().stream()
                            .map(e -> new Cookie.Builder()
                                .domain(url.host())
                                .name(e.getKey())
                                .value(e.getValue())
                                .build())
                            .toList();
                    }
                })
                .followRedirects(true)
                .build();
        }

        void get(String url) throws IOException {
            try (var r = client.newCall(req(url).get().build()).execute()) {
                // side effect: cookies
            }
        }

        String getHtml(String url) throws IOException {
            try (var r = client.newCall(req(url).get().build()).execute()) {
                return r.body() != null ? r.body().string() : "";
            }
        }

        String postJson(String url, RequestBody body, String xsrf, String referer) throws IOException {
            Request req = req(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", referer)
                .header("X-XSRFToken", xsrf)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body)
                .build();
            try (var r = client.newCall(req).execute()) {
                return r.body() != null ? r.body().string() : "{}";
            }
        }

        String extractXsrf(String html) {
            var m = XSRF_TOKEN_RE.matcher(html);
            if (m.find()) return m.group(1);
            return cookies.get("_xsrf");
        }

        java.util.Map<String, String> getCookies() { return java.util.Map.copyOf(cookies); }

        void restoreCookies(java.util.Map<String, String> saved) { cookies.putAll(saved); }

        void setCookie(String name, String value) { cookies.put(name, value); }

        private Request.Builder req(String url) {
            return new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "ru-RU,ru;q=0.9");
        }

        @Override public void close() {}
    }
}
