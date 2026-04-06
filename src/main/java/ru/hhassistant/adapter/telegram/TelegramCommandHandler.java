package ru.hhassistant.adapter.telegram;

import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.hhassistant.application.VacancyStateService;
import ru.hhassistant.domain.port.SearchSessionRepository;
import ru.hhassistant.domain.port.UserSettingsRepository;
import ru.hhassistant.infrastructure.hh.HhAuthenticatedWebClient;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@ApplicationScoped
public class TelegramCommandHandler {
  @Inject
  UserSettingsRepository userSettingsRepository;
  @Inject
  SearchSessionRepository sessionRepository;
  @Inject
  VacancyStateService stateService;
  @Inject
  HhAuthenticatedWebClient hhWebClient;
  @Inject
  TelegramOutboundClient outbound;
  @Inject
  TelegramMessageFormatter formatter;

  enum State {
    NONE, SETUP_KEYWORDS, SETUP_LETTER, SETUP_EMAIL,
    SETUP_OTP, SETUP_PASSWORD, SELECT_RESUME,
    MAIN_MENU, EDIT_KEYWORDS, EDIT_LETTER, EDIT_EMAIL,
    EDIT_OTP, EDIT_PASSWORD, SEARCHING
  }

  private final ConcurrentHashMap<Long, State> userState = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Map<String, Object>> userCtx = new ConcurrentHashMap<>();

  public void handleUpdate(Update update) {
    try {
      if (update.message() != null) {
        handleMessage(update.message());
      } else if (update.callbackQuery() != null) {
        handleCallback(update.callbackQuery());
      }
    } catch (Exception ex) {
      log.error("telegram.handler_error updateId={}", update.updateId());
    }
  }

  private void handleMessage(Message msg) {
    long chatId = msg.chat().id();
    String text = msg.text() != null ? msg.text().strip() : "";

    if (text.equals("/start")) {
      handleStart(chatId);
      return;
    }

    State state = userState.getOrDefault(chatId, State.NONE);
    switch (state) {
      case SETUP_KEYWORDS, EDIT_KEYWORDS -> handleKeywordsInput(chatId, text);
      case SETUP_LETTER, EDIT_LETTER -> handleLetterInput(chatId, text);
      case SETUP_EMAIL, EDIT_EMAIL -> handleEmailInput(chatId, text);
      case SETUP_OTP, EDIT_OTP -> handleOtpInput(chatId, text);
      case SETUP_PASSWORD, EDIT_PASSWORD -> handlePasswordInput(chatId, text);
      default -> {
      }
    }
  }

  private void handleCallback(CallbackQuery cq) {
    long chatId = cq.message().chat().id();
    int messageId = cq.message().messageId();
    String data = cq.data();

    switch (data) {
      case "start_search" -> handleStartSearch(chatId, messageId);
      case "stop_search" -> handleStopSearch(chatId, messageId);
      case "edit_keywords" -> {
        userState.put(chatId, State.EDIT_KEYWORDS);
        outbound.editMessage(chatId, messageId, "✏️ Введите новые ключевые слова через запятую:", null);
      }
      case "edit_letter" -> {
        userState.put(chatId, State.EDIT_LETTER);
        outbound.editMessage(chatId, messageId, "✏️ Введите новый текст письма или «пропустить»:",
          skipLetterKeyboard());
      }
      case "edit_credentials" -> {
        userState.put(chatId, State.EDIT_EMAIL);
        outbound.editMessage(chatId, messageId, "✏️ Введите новый email hh.ru:", null);
      }
      case "skip_letter" -> {
        userSettingsRepository.updateCoverLetter(chatId, null);
        showMainMenu(chatId, messageId);
      }
      case "reset_history" -> {
        int count = stateService.resetHistory(chatId);
        showMainMenu(chatId, messageId);
        outbound.sendHtml(chatId, "🗑 История откликов очищена (" + count + " записей).");
      }
      case "back_to_menu" -> {
        userState.put(chatId, State.MAIN_MENU);
        showMainMenu(chatId, messageId);
      }
      default -> {
        if (data.startsWith("resume:")) {
          String resumeId = data.substring(7);
          handleResumeSelected(chatId, messageId, resumeId);
        }
      }
    }
  }

  private void handleStart(long chatId) {
    var settingsOpt = userSettingsRepository.findByChatId(chatId);
    if (settingsOpt.isPresent() && isComplete(settingsOpt.get())) {
      userState.put(chatId, State.MAIN_MENU);
      var s = settingsOpt.get();
      outbound.sendHtml(chatId,
        formatter.formatSettingsMenu(s.keywords(), s.coverLetter() != null, s.email(), s.resumeTitle()),
        mainMenuKeyboard());
    } else {
      userState.put(chatId, State.SETUP_KEYWORDS);
      outbound.sendHtml(chatId,
        "👋 <b>HH Vacancy Assistant</b>\n\nАвтоматические отклики на hh.ru. Настроим за 3 шага.\n\n"
          + "🔍 <b>Шаг 1 из 3 — Ключевые слова</b>\n\n"
          + "Введите через запятую:\n<code>Python разработчик, Python backend</code>");
    }
  }

  private void handleKeywordsInput(long chatId, String text) {
    List<String> keywords = Arrays.stream(text.replace("\n", ",").split(","))
      .map(String::strip).filter(s -> !s.isBlank()).toList();
    if (keywords.isEmpty()) {
      outbound.sendHtml(chatId, "⚠️ Введите хотя бы одно ключевое слово.");
      return;
    }
    userSettingsRepository.updateKeywords(chatId, keywords);
    userState.put(chatId, State.SETUP_LETTER);
    outbound.sendHtml(chatId,
      "✅ Сохранено " + keywords.size() + " ключевых слов.\n\n"
        + "✉️ <b>Шаг 2 из 3 — Письмо</b>\n\n"
        + "Введите текст или нажмите «Без письма».\n"
        + "Переменные: <code>{title}</code>, <code>{employer}</code>",
      skipLetterKeyboard());
  }

  private void handleLetterInput(long chatId, String text) {
    String error = ru.hhassistant.application.CoverLetterRenderer.validate(text);
    if (error != null) {
      outbound.sendHtml(chatId, "⚠️ " + error);
      return;
    }
    userSettingsRepository.updateCoverLetter(chatId, text);
    userState.put(chatId, State.SETUP_EMAIL);
    outbound.sendHtml(chatId,
      "✅ Письмо сохранено.\n\n🔑 <b>Шаг 3 из 3 — Email hh.ru</b>\n\nВведите email аккаунта:");
  }

  private void handleEmailInput(long chatId, String email) {
    if (!email.contains("@")) {
      outbound.sendHtml(chatId, "⚠️ Похоже, это не email. Попробуйте ещё раз.");
      return;
    }
    outbound.sendHtml(chatId, "⏳ Проверяю аккаунт...");
    var init = hhWebClient.initiateLogin(email);
    if (init.isError()) {
      outbound.sendHtml(chatId, "❌ " + init.errorMessage());
      return;
    }
    userCtx.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>())
      .put("pending_email", email);
    userCtx.get(chatId).put("login_info", init);

    if (init.method() == HhAuthenticatedWebClient.LoginInitResult.Method.PASSWORD) {
      userState.put(chatId, State.SETUP_PASSWORD);
      outbound.sendHtml(chatId, "🔐 <b>Пароль от hh.ru</b>\n\nВведите пароль:");
    } else {
      userState.put(chatId, State.SETUP_OTP);
      String msg = init.alreadySent()
        ? "📧 Код уже был отправлен — проверьте входящие и папку «Спам». Введите код:"
        : "📧 Код отправлен на ваш email. Введите его:";
      outbound.sendHtml(chatId, msg);
    }
  }

  private void handleOtpInput(long chatId, String code) {
    var ctx = userCtx.getOrDefault(chatId, Map.of());
    String email = (String) ctx.get("pending_email");
    var init = (HhAuthenticatedWebClient.LoginInitResult) ctx.get("login_info");
    if (email == null || init == null) {
      handleStart(chatId);
      return;
    }

    outbound.sendHtml(chatId, "⏳ Проверяю код...");
    var result = hhWebClient.completeOtpLogin(email, code, init);
    if (!result.success()) {
      outbound.sendHtml(chatId, "❌ Неверный код. Попробуйте ещё раз:");
      return;
    }
    finishAuth(chatId, email, result.hhtoken());
  }

  private void handlePasswordInput(long chatId, String password) {
    var ctx = userCtx.getOrDefault(chatId, Map.of());
    String email = (String) ctx.get("pending_email");
    var init = (HhAuthenticatedWebClient.LoginInitResult) ctx.get("login_info");
    if (email == null || init == null) {
      handleStart(chatId);
      return;
    }

    outbound.sendHtml(chatId, "⏳ Выполняю вход...");
    var result = hhWebClient.completePasswordLogin(email, password, init);
    if (!result.success()) {
      outbound.sendHtml(chatId, "❌ Неверный пароль. Попробуйте ещё раз:");
      return;
    }
    finishAuth(chatId, email, result.hhtoken());
  }

  private void finishAuth(long chatId, String email, String hhtoken) {
    userSettingsRepository.updateAuth(chatId, email, hhtoken);
    userCtx.remove(chatId);

    var resumes = hhWebClient.getResumes(hhtoken);
    if (resumes.isEmpty()) {
      outbound.sendHtml(chatId, "❌ Нет резюме на аккаунте. Создайте резюме на hh.ru и нажмите /start.");
      return;
    }
    if (resumes.size() == 1) {
      var r = resumes.get(0);
      userSettingsRepository.updateResume(chatId, r.resumeId(), r.resumeTitle());
      outbound.sendHtml(chatId, "✅ Вход выполнен · Резюме: <b>" + TelegramMessageFormatter.esc(r.resumeTitle()) + "</b>");
      showMainMenu(chatId);
    } else {
      outbound.sendHtml(chatId,
        "✅ Вход выполнен. Найдено " + resumes.size() + " резюме. Выберите одно:",
        resumeKeyboard(resumes));
      userState.put(chatId, State.SELECT_RESUME);
    }
  }

  private void handleResumeSelected(long chatId, int messageId, String resumeId) {
    var resumes = userSettingsRepository.findByChatId(chatId)
      .map(r -> hhWebClient.getResumes(r.hhtoken())).orElse(List.of());
    String title = resumes.stream()
      .filter(r -> resumeId.equals(r.resumeId())).findFirst()
      .map(UserSettingsRepository.UserSettingsRow::resumeTitle)
      .orElse(resumeId);
    userSettingsRepository.updateResume(chatId, resumeId, title);
    outbound.editMessage(chatId, messageId, "✅ Резюме: <b>" + TelegramMessageFormatter.esc(title) + "</b>", null);
    showMainMenu(chatId);
  }

  private void handleStartSearch(long chatId, int messageId) {
    var settings = userSettingsRepository.findByChatId(chatId).orElse(null);
    if (settings == null || !isComplete(settings)) {
      outbound.sendHtml(chatId, "⚠️ Настройки неполные. Нажмите /start для настройки.");
      return;
    }
    sessionRepository.start(chatId, Instant.now());
    userState.put(chatId, State.SEARCHING);
    List<String> kws = settings.keywords();
    String kwText = String.join("\n", kws.subList(0, Math.min(10, kws.size()))
      .stream().map(k -> "• " + TelegramMessageFormatter.esc(k)).toList());
    outbound.editMessage(chatId, messageId,
      "🚀 <b>Поиск запущен!</b>\n\nКлючевые слова:\n" + kwText
        + "\n\nПочасовая статистика — автоматически.",
      stopKeyboard());
    log.info("search.started chatId={}", chatId);
  }

  private void handleStopSearch(long chatId, int messageId) {
    sessionRepository.clear(chatId);
    userState.put(chatId, State.MAIN_MENU);
    outbound.editMessage(chatId, messageId, "⏹ Поиск остановлен.", null);
    outbound.sendHtml(chatId, "Итоги сессии будут в следующем сообщении.");
    log.info("search.stopped chatId={}", chatId);
  }


  private static InlineKeyboardMarkup mainMenuKeyboard() {
    return new InlineKeyboardMarkup(
      new InlineKeyboardButton[]{
        new InlineKeyboardButton("✏️ Ключевые слова").callbackData("edit_keywords"),
        new InlineKeyboardButton("✉️ Письмо").callbackData("edit_letter")
      },
      new InlineKeyboardButton[]{
        new InlineKeyboardButton("👤 Аккаунт").callbackData("edit_credentials"),
        new InlineKeyboardButton("🗑 Очистить историю").callbackData("reset_history")
      },
      new InlineKeyboardButton[]{
        new InlineKeyboardButton("▶️ Запустить поиск").callbackData("start_search")
      }
    );
  }

  private static InlineKeyboardMarkup stopKeyboard() {
    return new InlineKeyboardMarkup(
      new InlineKeyboardButton[]{
        new InlineKeyboardButton("⏹ Остановить").callbackData("stop_search")
      }
    );
  }

  private static InlineKeyboardMarkup skipLetterKeyboard() {
    return new InlineKeyboardMarkup(
      new InlineKeyboardButton[]{
        new InlineKeyboardButton("Без письма").callbackData("skip_letter")
      }
    );
  }

  private static InlineKeyboardMarkup resumeKeyboard(
    List<UserSettingsRepository.UserSettingsRow> resumes
  ) {
    var rows = resumes.stream()
      .map(r -> new InlineKeyboardButton[]{
        new InlineKeyboardButton(TelegramMessageFormatter.esc(r.resumeTitle()))
          .callbackData("resume:" + r.resumeId())
      })
      .toList();
    return new InlineKeyboardMarkup(rows.toArray(InlineKeyboardButton[][]::new));
  }

  private static InlineKeyboardMarkup backKeyboard() {
    return new InlineKeyboardMarkup(
      new InlineKeyboardButton[]{
        new InlineKeyboardButton("◀️ Главное меню").callbackData("back_to_menu")
      }
    );
  }

  private void showMainMenu(long chatId) {
    userSettingsRepository.findByChatId(chatId).ifPresentOrElse(
      s -> outbound.sendHtml(chatId,
        formatter.formatSettingsMenu(s.keywords(), s.coverLetter() != null, s.email(), s.resumeTitle()),
        mainMenuKeyboard()),
      () -> outbound.sendHtml(chatId, "Настройки не найдены. /start для начала.")
    );
    userState.put(chatId, State.MAIN_MENU);
  }

  private void showMainMenu(long chatId, int messageId) {
    userSettingsRepository.findByChatId(chatId).ifPresentOrElse(
      s -> outbound.editMessage(chatId, messageId,
        formatter.formatSettingsMenu(s.keywords(), s.coverLetter() != null, s.email(), s.resumeTitle()),
        mainMenuKeyboard()),
      () -> outbound.sendHtml(chatId, "Настройки не найдены.")
    );
    userState.put(chatId, State.MAIN_MENU);
  }

  private static boolean isComplete(UserSettingsRepository.UserSettingsRow s) {
    return s.resumeId() != null && !s.resumeId().isBlank()
      && !s.keywords().isEmpty()
      && s.email() != null
      && s.hhtoken() != null;
  }
}
