package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.AnswerPreCheckoutQuery;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.SendResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tgbotgpt.service.BotAdminService;
import tgbotgpt.service.BotMetricsService;
import tgbotgpt.service.DocumentExtractionResult;
import tgbotgpt.service.DocumentService;
import tgbotgpt.service.ImageService;
import tgbotgpt.service.ImageDownloadResult;
import tgbotgpt.service.StarsPaymentService;
import tgbotgpt.service.openai.GptService;
import tgbotgpt.utils.LogUtils;
import tgbotgpt.utils.TelegramUtils;
import tgbotgpt.utils.UpdateUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;

@Slf4j
@Service
public class TelegramBotService {

    private static final int STREAM_UPDATE_INTERVAL_MS = 800;
    private static final String ACCESS_DENIED_MESSAGE = "Sorry, you are not in the access list.";
    private static final String WELCOME_MESSAGE = """
            Hello! I am GPTbot in Telegram.

            I can answer questions, keep recent private-chat context, analyze photos, and read PDF/TXT documents.

            Send a message to start. Use /help for commands, /settings for model and limits, or /plan to request Pro.
            """;
    private static final String HELP_MESSAGE = """
            GPTbot help

            Main workflows:
            - Send a message to chat with GPT.
            - Send a photo for image analysis.
            - Send a PDF or TXT file for document analysis.
            - Mention @%s in a group chat to ask the bot.

            Commands:
            /examples - practical prompt examples
            /settings - model, prompt, usage, and limits
            /model - choose a model
            /prompt <text> - set a custom system prompt
            /prompt reset - reset your prompt
            /balance - current plan and remaining limits
            /plan - available plans and Pro request
            /upgrade - request Pro from the owner
            /reset - clear private-chat history
            """;
    private static final String EXAMPLES_MESSAGE = """
            GPTbot examples

            Chat:
            Goal: choose the best deployment option.
            Context: small Telegram bot, one maintainer, PostgreSQL, Docker Compose.
            Output: compare 3 options and recommend one.

            Document caption:
            Summarize key decisions, risks, owners, and deadlines. If an owner is missing, write "unassigned".

            Image caption:
            Review this screenshot and suggest what I should click or check next.

            Custom prompt:
            /prompt Answer in Russian unless I ask otherwise. Be concise, practical, and challenge weak assumptions.

            Limits:
            Use /settings for model and prompt, /balance for remaining quota, and /plan or /upgrade for Pro.
            """;

    private final GptService gptService;
    private final ImageService imageService;
    private final DocumentService documentService;
    private final BotAdminService adminService;
    private final BotMetricsService metrics;
    private final StarsPaymentService starsPaymentService;
    private final TelegramSender telegramSender;
    private final AdminCommandHandler adminCommandHandler;

    @Value("${bot.token}")
    private String botToken;
    @Value("${bot.name}")
    private String botName;
    @Value("${bot.stream.enabled:true}")
    private boolean streamEnabled;
    @Value("${bot.executor.threads:0}")
    private int executorThreads;
    @Value("${bot.executor.queue.size:128}")
    private int executorQueueSize;
    @Value("${bot.shutdown.timeout.seconds:30}")
    private int shutdownTimeoutSeconds;
    @Value("${bot.telegram.retry.max.backoff.ms:5000}")
    private long telegramRetryMaxBackoffMs;

    private TelegramBot bot;
    private ThreadPoolExecutor executorService;

    public TelegramBotService(GptService gptService, ImageService imageService, DocumentService documentService,
                              BotAdminService adminService, BotMetricsService metrics,
                              StarsPaymentService starsPaymentService, TelegramSender telegramSender,
                              AdminCommandHandler adminCommandHandler) {
        this.gptService = gptService;
        this.imageService = imageService;
        this.documentService = documentService;
        this.adminService = adminService;
        this.metrics = metrics;
        this.starsPaymentService = starsPaymentService;
        this.telegramSender = telegramSender;
        this.adminCommandHandler = adminCommandHandler;
    }

    @PostConstruct
    private void init() {
        int threads = executorThreads > 0 ? executorThreads : Runtime.getRuntime().availableProcessors();
        executorService = new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(executorQueueSize),
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.bot = new TelegramBot(botToken);
        bot.setUpdatesListener(this::handleUpdates);
    }

    /**
     * Submits updates one-by-one and returns the highest update_id we successfully
     * accepted. If the bounded queue rejects an update, we stop and let Telegram
     * redeliver from that point on the next long-poll. This prevents both unbounded
     * memory growth and silent loss of unprocessed updates.
     */
    int handleUpdates(java.util.List<Update> updates) {
        int lastConfirmed = UpdatesListener.CONFIRMED_UPDATES_NONE;
        for (Update update : updates) {
            try {
                executorService.submit(() -> processUpdate(update));
                lastConfirmed = update.updateId();
            } catch (RejectedExecutionException rejected) {
                metrics.recordExecutorRejection();
                log.warn("Executor saturated; deferring update {} for redelivery", update.updateId());
                break;
            }
        }
        return lastConfirmed;
    }

    @PreDestroy
    private void dispose() {
        log.info("Shutting down bot (graceful timeout {}s)", shutdownTimeoutSeconds);
        // 1. Stop polling for new updates so the executor queue can stop growing.
        bot.removeGetUpdatesListener();
        // 2. Drain in-flight tasks BEFORE closing the HTTP client — otherwise
        //    sendReply / editMessage / GetFile in running tasks would fail.
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in {}s; forcing shutdownNow", shutdownTimeoutSeconds);
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
        // 3. Close the HTTP client only after the executor is drained.
        bot.shutdown();
    }

    private void processUpdate(Update update) {
        String operation = operationName(update);
        Instant startedAt = Instant.now();
        boolean success = false;
        MDC.put("request_id", UUID.randomUUID().toString());
        MDC.put("operation", operation);
        MDC.put("user_hash", LogUtils.hashUserId(userId(update)));
        try {
            processUpdateInternal(update);
            success = true;
        } catch (RuntimeException e) {
            log.error("Unhandled Telegram update processing failure for update {}",
                    update != null ? update.updateId() : null, e);
        } finally {
            Duration duration = Duration.between(startedAt, Instant.now());
            metrics.recordOperationDuration(operation, success, duration);
            log.info("operation={} result={} duration_ms={}", operation, success ? "success" : "failure", duration.toMillis());
            MDC.clear();
        }
    }

    private void processUpdateInternal(Update update) {
        if (update.preCheckoutQuery() != null) {
            handlePreCheckoutQuery(update.preCheckoutQuery());
            return;
        }

        if (update.callbackQuery() != null) {
            processCallback(update.callbackQuery());
            return;
        }

        if (update.message() == null) return;

        if (update.message().successfulPayment() != null) {
            if (ensureAllowed(update)) handleSuccessfulPayment(update);
            return;
        }

        // Handle documents
        if (update.message().document() != null) {
            if (UpdateUtils.isPrivate(update) || hasBotMention(update)) {
                if (ensureAllowed(update)) processDocument(update);
            }
            return;
        }

        // Handle photos
        if (update.message().photo() != null && update.message().photo().length > 0) {
            if (UpdateUtils.isPrivate(update) || hasBotMention(update)) {
                if (ensureAllowed(update)) processPhoto(update);
            }
            return;
        }

        // Handle text
        if (update.message().text() != null) {
            if (update.message().text().startsWith("/")) {
                if (ensureAllowed(update)) processCommand(update);
            } else {
                if (UpdateUtils.isPrivate(update) || hasBotMention(update)) {
                    if (ensureAllowed(update)) processText(update);
                }
            }
        }
    }

    /**
     * Fail-closed gate for any message interaction. Runs before we touch user state,
     * download files, or call the model, so unauthorized users never create DB rows,
     * leak plan/usage info, or cost us a Telegram file download.
     */
    private boolean ensureAllowed(Update update) {
        Message message = update != null ? update.message() : null;
        User from = message != null ? message.from() : null;
        Chat chat = message != null ? message.chat() : null;
        Long fromId = from != null ? from.id() : null;
        String username = from != null ? from.username() : null;
        String chatTitle = chat != null ? chat.title() : null;

        if (gptService.isAllowed(fromId, username, chatTitle)) {
            return true;
        }
        if (chat != null) {
            sendReply(update, ACCESS_DENIED_MESSAGE);
        } else {
            log.warn("Access denied for update without reply chat");
        }
        return false;
    }

    private boolean isCallbackAllowed(CallbackQuery callback) {
        Long fromId = callback.from() != null ? callback.from().id() : null;
        String username = callback.from() != null ? callback.from().username() : null;
        String chatTitle = callback.message() != null && callback.message().chat() != null
                ? callback.message().chat().title()
                : null;
        return gptService.isAllowed(fromId, username, chatTitle);
    }

    private boolean isPreCheckoutAllowed(PreCheckoutQuery query) {
        Long fromId = query.from() != null ? query.from().id() : null;
        String username = query.from() != null ? query.from().username() : null;
        return gptService.isAllowed(fromId, username, null);
    }

    private boolean hasBotMention(Update update) {
        String text = update.message().text() != null ? update.message().text() : "";
        String caption = update.message().caption() != null ? update.message().caption() : "";
        String combined = text + " " + caption;
        return combined.toLowerCase().contains("@" + botName.toLowerCase());
    }

    private void processDocument(Update update) {
        Document doc = update.message().document();
        String fileName = doc.fileName();
        log.info("User {} sent a document with extension {}", update.message().from().id(), documentService.getExtension(fileName));

        if (!documentService.isSupported(fileName)) {
            sendReply(update, "Unsupported file type. Supported: PDF, TXT.");
            return;
        }

        GetFileResponse fileResponse = bot.execute(new GetFile(doc.fileId()));
        if (!fileResponse.isOk()) {
            sendReply(update, "Failed to download the document.");
            return;
        }

        String fileUrl = bot.getFullFilePath(fileResponse.file());
        DocumentExtractionResult result = documentService.extractText(fileUrl, fileName);
        if (!result.isSuccess()) {
            sendReply(update, mapDocumentError(result.status()));
            return;
        }

        String caption = update.message().caption();
        String response = gptService.sendDocumentMessage(update, result.text(), caption);
        sendLongReply(update, response);
    }

    private void processPhoto(Update update) {
        log.info("User {} sent a photo", update.message().from().id());

        // Get the largest photo size
        PhotoSize[] photos = update.message().photo();
        PhotoSize largest = photos[photos.length - 1];

        GetFileResponse fileResponse = bot.execute(new GetFile(largest.fileId()));
        if (!fileResponse.isOk()) {
            sendReply(update, "Failed to process the image.");
            return;
        }

        String filePath = fileResponse.file().filePath();
        String fileUrl = bot.getFullFilePath(fileResponse.file());
        String mimeType = imageService.guessMimeType(filePath);

        ImageDownloadResult result = imageService.downloadAndEncode(fileUrl, mimeType);
        if (!result.isSuccess()) {
            sendReply(update, mapImageError(result.status()));
            return;
        }

        String caption = update.message().caption();
        String response = gptService.sendVisionMessage(update, result.base64(), mimeType, caption);
        sendLongReply(update, response);
    }

    private void processText(Update update) {
        log.info("User {} sent a text message", update.message().from().id());

        if (streamEnabled && UpdateUtils.isPrivate(update)) {
            processTextStream(update);
        } else {
            String response = gptService.sendMessage(update);
            notifyOwnersOnOpenAiQuotaIssue(response);
            log.info("{} generated a response", botName);
            sendLongReply(update, response);
        }
    }

    private void processTextStream(Update update) {
        SendMessage placeholder = new SendMessage(update.message().chat().id(), "...")
                .disableNotification(true);
        SendResponse sendResponse = bot.execute(placeholder);

        if (!sendResponse.isOk()) {
            log.error("Failed to send placeholder: {}", sendResponse.description());
            return;
        }

        int messageId = sendResponse.message().messageId();
        long chatId = update.message().chat().id();
        StringBuilder accumulated = new StringBuilder();
        AtomicReference<String> lastSent = new AtomicReference<>("");
        AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());

        gptService.sendMessageStream(update)
                .doOnNext(chunk -> {
                    accumulated.append(chunk);
                    long now = System.currentTimeMillis();
                    if (now - lastUpdateTime.get() >= STREAM_UPDATE_INTERVAL_MS) {
                        String current = TelegramUtils.truncateForEdit(accumulated.toString());
                        if (!current.equals(lastSent.get())) {
                            editMessage(chatId, messageId, current);
                            lastSent.set(current);
                            lastUpdateTime.set(now);
                        }
                    }
                })
                .doOnComplete(() -> {
                    String finalText = accumulated.toString();
                    if (finalText.isEmpty()) {
                        editMessage(chatId, messageId, "No response received.");
                        log.info("{} generated an empty streaming response", botName);
                        return;
                    }

                    List<String> parts = TelegramUtils.splitMessage(finalText);
                    String firstPart = parts.get(0);
                    if (!firstPart.equals(lastSent.get())) {
                        editMessage(chatId, messageId, firstPart);
                    }
                    for (int i = 1; i < parts.size(); i++) {
                        sendReply(update, parts.get(i));
                    }
                    log.info("{} generated a streaming response", botName);
                })
                .onErrorResume(e -> {
                    log.error("Stream error: ", e);
                    String fallback = gptService.sendMessageAfterStreamFailure(update);
                    notifyOwnersOnOpenAiQuotaIssue(fallback);
                    List<String> parts = TelegramUtils.splitMessage(fallback);
                    editMessage(chatId, messageId, parts.get(0));
                    for (int i = 1; i < parts.size(); i++) {
                        sendReply(update, parts.get(i));
                    }
                    return Flux.empty();
                })
                .blockLast();
    }

    private void editMessage(long chatId, int messageId, String text) {
        telegramSender.editMessage(bot, chatId, messageId, text, telegramRetryMaxBackoffMs);
    }

    /**
     * Honors Telegram's 429 retry_after once. If retry_after exceeds the
     * configured backoff cap we skip the retry rather than sleep-and-fail —
     * sleeping the cap and resending is guaranteed to be rate-limited again.
     * Other failures (4xx other than 429) are returned as-is — caller decides
     * whether to fall back (e.g. plain-text after Markdown 400).
     */
    <T extends BaseResponse> T executeWithRetry(BaseRequest<?, T> request, String operation) {
        return telegramSender.executeWithRetry(bot, request, operation, telegramRetryMaxBackoffMs);
    }

    private void sendLongReply(Update update, String message) {
        telegramSender.sendLongReply(bot, update, message, telegramRetryMaxBackoffMs);
    }

    private void sendReply(Update update, String message) {
        telegramSender.sendReply(bot, update, message, telegramRetryMaxBackoffMs);
    }

    private String mapDocumentError(DocumentExtractionResult.Status status) {
        return switch (status) {
            case UNSUPPORTED_TYPE -> "Unsupported file type. Supported: PDF, TXT.";
            case TOO_LARGE -> "Document is too large.";
            case TIMEOUT -> "Document processing timed out. Try a smaller file.";
            case INVALID_SOURCE -> "Failed to download the document.";
            case UNREADABLE -> "Could not read the document. It may be empty or corrupted.";
            case SUCCESS -> throw new IllegalArgumentException("Success status should not be mapped as an error");
        };
    }

    private String mapImageError(ImageDownloadResult.Status status) {
        return switch (status) {
            case UNSUPPORTED_TYPE -> "Unsupported image format.";
            case TOO_LARGE -> "Image is too large.";
            case INVALID_SOURCE -> "Failed to process the image.";
            case UNREADABLE -> "Could not read the image.";
            case SUCCESS -> throw new IllegalArgumentException("Success status should not be mapped as an error");
        };
    }

    private void processCommand(Update update) {
        String text = update.message().text().toLowerCase();

        if (text.startsWith("/model")) {
            handleModelCommand(update);
            return;
        }
        if (text.startsWith("/prompt")) {
            handlePromptCommand(update);
            return;
        }
        if (text.startsWith("/status")) {
            handleStatusCommand(update);
            return;
        }
        if (text.startsWith("/settings")) {
            handleSettingsCommand(update);
            return;
        }
        if (text.startsWith("/balance")) {
            handleBalanceCommand(update);
            return;
        }
        if (text.startsWith("/plan") || text.startsWith("/plans")) {
            handlePlanCommand(update);
            return;
        }
        if (text.startsWith("/upgrade")) {
            handleUpgradeCommand(update);
            return;
        }
        if (text.startsWith("/help")) {
            sendHelp(update);
            return;
        }
        if (text.startsWith("/examples")) {
            sendExamples(update);
            return;
        }
        if (text.startsWith("/admin")) {
            handleAdminCommand(update);
            return;
        }

        switch (text) {
            case "/start":
                sendWelcome(update);
                break;
            case "/usage":
                printUsage(update);
                break;
            case "/reset":
                resetUserContext(update);
                break;
            default:
                log.warn("Unknown command from user {}", update.message().from().id());
        }
    }

    private void handleModelCommand(Update update) {
        String text = update.message().text().trim();
        Long userId = update.message().from().id();

        if (text.equalsIgnoreCase("/model")) {
            String current = gptService.getUserModel(userId);
            sendModelMenu(update, "Current model: " + current);
            return;
        }

        String modelName = text.substring("/model".length()).trim();
        String result = gptService.setUserModel(userId, modelName);
        sendReply(update, result);
    }

    private void sendModelMenu(Update update, String header) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        for (String model : gptService.getAvailableModels().split(",")) {
            String trimmed = model.trim();
            if (!trimmed.isEmpty()) {
                keyboard.addRow(new InlineKeyboardButton(trimmed).callbackData("model:" + trimmed));
            }
        }
        SendMessage request = new SendMessage(update.message().chat().id(), header + "\nChoose a model:")
                .disableWebPagePreview(true)
                .disableNotification(true)
                .replyMarkup(keyboard);
        SendResponse response = executeWithRetry(request, "send");
        metrics.recordTelegramSend(false, response.isOk());
        if (!response.isOk()) {
            log.error("Failed to send model menu: {}", response.description());
        }
    }

    private void processCallback(CallbackQuery callback) {
        if (!isCallbackAllowed(callback)) {
            bot.execute(new AnswerCallbackQuery(callback.id()).text(ACCESS_DENIED_MESSAGE));
            return;
        }

        String data = callback.data();
        if (data == null) {
            bot.execute(new AnswerCallbackQuery(callback.id()).text("Unsupported action"));
            return;
        }

        if (data.startsWith("model:")) {
            handleModelCallback(callback, data);
            return;
        }
        if ("plan:upgrade".equals(data)) {
            handleUpgradeCallback(callback);
            return;
        }
        if ("plan:buy".equals(data)) {
            handleBuyCallback(callback);
            return;
        }

        bot.execute(new AnswerCallbackQuery(callback.id()).text("Unsupported action"));
    }

    private void handleModelCallback(CallbackQuery callback, String data) {
        String model = data.substring("model:".length()).trim();
        String result = gptService.setUserModel(callback.from().id(), model);
        bot.execute(new AnswerCallbackQuery(callback.id()).text(result));
        if (callback.message() != null) {
            editMessage(callback.message().chat().id(), callback.message().messageId(),
                    result + "\n\n" + gptService.getSettingsSummary(callback.from().id()));
        }
    }

    private void handleUpgradeCallback(CallbackQuery callback) {
        GptService.UpgradeRequest request = gptService.createUpgradeRequest(callback.from().id());
        bot.execute(new AnswerCallbackQuery(callback.id()).text(request.userMessage()));
        if (request.notifyOwners()) {
            notifyOwners(request.ownerMessage());
        }
        if (callback.message() != null) {
            editMessage(callback.message().chat().id(), callback.message().messageId(), request.userMessage());
        }
    }

    private void handleBuyCallback(CallbackQuery callback) {
        if (callback.message() == null || callback.message().chat() == null) {
            bot.execute(new AnswerCallbackQuery(callback.id()).text("Use /upgrade to buy PRO."));
            return;
        }
        boolean sent = sendProInvoice(callback.message().chat().id(), callback.from().id());
        if (sent) {
            bot.execute(new AnswerCallbackQuery(callback.id()).text("Invoice sent."));
            return;
        }

        bot.execute(new AnswerCallbackQuery(callback.id()).text("Invoice failed. Manual request sent."));
        GptService.UpgradeRequest request = gptService.createUpgradeRequest(callback.from().id());
        if (request.notifyOwners()) {
            notifyOwners(request.ownerMessage());
        }
        editMessage(callback.message().chat().id(), callback.message().messageId(), request.userMessage());
    }

    private void handlePromptCommand(Update update) {
        String text = update.message().text().trim();
        Long userId = update.message().from().id();

        if (text.equalsIgnoreCase("/prompt")) {
            sendReply(update, "Usage: /prompt <your custom system prompt>\n/prompt reset - reset to default");
            return;
        }

        String promptText = text.substring("/prompt".length()).trim();

        if ("reset".equalsIgnoreCase(promptText)) {
            String result = gptService.resetUserPrompt(userId);
            sendReply(update, result);
            return;
        }

        String result = gptService.setUserPrompt(userId, promptText);
        sendReply(update, result);
    }

    private void handleStatusCommand(Update update) {
        Long userId = update.message().from().id();
        sendReply(update, adminService.statusFor(userId));
    }

    private void handleSettingsCommand(Update update) {
        Long userId = update.message().from().id();
        sendReply(update, gptService.getSettingsSummary(userId));
    }

    private void handleBalanceCommand(Update update) {
        Long userId = update.message().from().id();
        sendReply(update, gptService.getBalanceSummary(userId));
    }

    private void handlePlanCommand(Update update) {
        String text = update.message().text().trim();
        Long userId = update.message().from().id();

        if (text.equalsIgnoreCase("/plan") || text.equalsIgnoreCase("/plans")) {
            sendPlanMenu(update, gptService.getPlanSummary(userId));
            return;
        }

        sendReply(update, "Usage: /plan, /plans, or /upgrade. Owner commands: /admin");
    }

    private void sendPlanMenu(Update update, String text) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.addRow(new InlineKeyboardButton(starsPaymentService.buyButtonText()).callbackData("plan:buy"));
        keyboard.addRow(new InlineKeyboardButton("Request PRO manually").callbackData("plan:upgrade"));
        SendMessage request = new SendMessage(update.message().chat().id(), text)
                .disableWebPagePreview(true)
                .disableNotification(true)
                .replyMarkup(keyboard);
        SendResponse response = executeWithRetry(request, "send");
        metrics.recordTelegramSend(false, response.isOk());
        if (!response.isOk()) {
            log.error("Failed to send plan menu: {}", response.description());
        }
    }

    private void handleUpgradeCommand(Update update) {
        if (sendProInvoice(update.message().chat().id(), update.message().from().id())) {
            return;
        }
        log.warn("Failed to send Stars invoice; falling back to manual upgrade request");
        handleManualUpgradeRequest(update);
    }

    private void handleManualUpgradeRequest(Update update) {
        GptService.UpgradeRequest request = gptService.createUpgradeRequest(update.message().from().id());
        sendReply(update, request.userMessage());
        if (request.notifyOwners()) {
            notifyOwners(request.ownerMessage());
        }
    }

    private boolean sendProInvoice(long chatId, long userId) {
        SendResponse response = starsPaymentService.sendInvoice(bot, chatId, userId);
        metrics.recordTelegramSend(false, response.isOk());
        if (!response.isOk()) {
            log.error("Failed to send Stars invoice: {}", response.description());
            return false;
        }
        return true;
    }

    private void handlePreCheckoutQuery(PreCheckoutQuery query) {
        if (!isPreCheckoutAllowed(query)) {
            bot.execute(new AnswerPreCheckoutQuery(query.id(), ACCESS_DENIED_MESSAGE));
            return;
        }

        Optional<String> validationError = starsPaymentService.validatePreCheckout(query);
        if (validationError.isPresent()) {
            bot.execute(new AnswerPreCheckoutQuery(query.id(), validationError.get()));
            return;
        }
        bot.execute(new AnswerPreCheckoutQuery(query.id()));
    }

    private void handleSuccessfulPayment(Update update) {
        Message message = update.message();
        SuccessfulPayment payment = message.successfulPayment();
        StarsPaymentService.PaymentResult result = starsPaymentService.processPayment(
                message.from().id(),
                payment.telegramPaymentChargeId(),
                payment.currency(),
                payment.totalAmount(),
                payment.invoicePayload()
        );
        sendReply(update, result.userMessage());
        if (result.notifyOwners()) {
            notifyOwners(result.ownerMessage());
        }
    }

    private void handleAdminCommand(Update update) {
        sendReply(update, adminCommandHandler.handle(update));
    }

    private void sendWelcome(Update update) {
        sendReply(update, WELCOME_MESSAGE.strip());
    }

    private void sendHelp(Update update) {
        sendReply(update, HELP_MESSAGE.formatted(botName).strip());
    }

    private void sendExamples(Update update) {
        sendReply(update, EXAMPLES_MESSAGE.strip());
    }

    private void printUsage(Update update) {
        Long userId = update.message().from().id();
        sendReply(update, gptService.getBalanceSummary(userId));
    }

    private void notifyOwnersOnOpenAiQuotaIssue(String response) {
        if (gptService.isOpenAiQuotaOrRateLimitIssue(response)) {
            notifyOwners("OpenAI quota/rate-limit issue detected. Check billing, project limits, and /status.");
        }
    }

    private void notifyOwners(String text) {
        Set<Long> ownerIds = adminService.getOwnerIds();
        if (ownerIds == null || ownerIds.isEmpty()) {
            return;
        }
        for (Long ownerId : ownerIds) {
            SendResponse response = executeWithRetry(new SendMessage(ownerId, text).disableNotification(false), "send");
            metrics.recordTelegramSend(false, response.isOk());
            if (!response.isOk()) {
                log.warn("Failed to notify owner {}: {}", LogUtils.hashUserId(ownerId), response.description());
            }
        }
    }

    private Long userId(Update update) {
        if (update == null) {
            return null;
        }
        if (update.message() != null && update.message().from() != null) {
            return update.message().from().id();
        }
        if (update.preCheckoutQuery() != null && update.preCheckoutQuery().from() != null) {
            return update.preCheckoutQuery().from().id();
        }
        if (update.callbackQuery() != null && update.callbackQuery().from() != null) {
            return update.callbackQuery().from().id();
        }
        return null;
    }

    private String operationName(Update update) {
        if (update == null) {
            return "unknown";
        }
        if (update.callbackQuery() != null) {
            return "callback";
        }
        if (update.preCheckoutQuery() != null) {
            return "pre_checkout";
        }
        if (update.message() == null) {
            return "empty";
        }
        if (update.message().successfulPayment() != null) {
            return "payment";
        }
        if (update.message().document() != null) {
            return "document";
        }
        if (update.message().photo() != null && update.message().photo().length > 0) {
            return "photo";
        }
        if (update.message().text() != null && update.message().text().startsWith("/")) {
            return "command";
        }
        if (update.message().text() != null) {
            return "text";
        }
        return "unknown";
    }

    private void resetUserContext(Update update) {
        String message = gptService.resetUserContext(update);
        sendReply(update, message);
    }
}
