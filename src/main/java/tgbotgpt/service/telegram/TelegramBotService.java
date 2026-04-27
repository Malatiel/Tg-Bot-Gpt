package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.model.request.ReplyKeyboardRemove;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.EditMessageText;
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
import tgbotgpt.service.BotAdminService;
import tgbotgpt.service.BotMetricsService;
import tgbotgpt.service.DocumentExtractionResult;
import tgbotgpt.service.DocumentService;
import tgbotgpt.service.ImageService;
import tgbotgpt.service.ImageDownloadResult;
import tgbotgpt.service.openai.GptService;
import tgbotgpt.utils.TelegramUtils;
import tgbotgpt.utils.UpdateUtils;

import java.util.List;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class TelegramBotService {

    private static final int STREAM_UPDATE_INTERVAL_MS = 800;

    private final GptService gptService;
    private final ImageService imageService;
    private final DocumentService documentService;
    private final BotAdminService adminService;
    private final BotMetricsService metrics;

    @Value("${bot.token}")
    private String botToken;
    @Value("${bot.name}")
    private String botName;
    @Value("${bot.presentation}")
    private String presentationText;
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
                              BotAdminService adminService, BotMetricsService metrics) {
        this.gptService = gptService;
        this.imageService = imageService;
        this.documentService = documentService;
        this.adminService = adminService;
        this.metrics = metrics;
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
        if (update.message() == null) return;

        // Handle documents
        if (update.message().document() != null) {
            if (UpdateUtils.isPrivate(update) || hasBotMention(update)) {
                processDocument(update);
            }
            return;
        }

        // Handle photos
        if (update.message().photo() != null && update.message().photo().length > 0) {
            if (UpdateUtils.isPrivate(update) || hasBotMention(update)) {
                processPhoto(update);
            }
            return;
        }

        // Handle text
        if (update.message().text() != null) {
            if (update.message().text().startsWith("/")) {
                processCommand(update);
            } else {
                if (UpdateUtils.isPrivate(update) || hasBotMention(update)) {
                    processText(update);
                }
            }
        }
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
                .doOnError(e -> {
                    log.error("Stream error: ", e);
                    editMessage(chatId, messageId, "Sorry, something went wrong.");
                })
                .blockLast();
    }

    private void editMessage(long chatId, int messageId, String text) {
        try {
            BaseResponse response = executeWithRetry(
                    new EditMessageText(chatId, messageId, TelegramUtils.truncateForEdit(text)),
                    "edit");
            metrics.recordTelegramEdit(response.isOk());
        } catch (Exception e) {
            metrics.recordTelegramEdit(false);
            log.error("Failed to edit message: ", e);
        }
    }

    /**
     * Honors Telegram's 429 retry_after once. If retry_after exceeds the
     * configured backoff cap we skip the retry rather than sleep-and-fail —
     * sleeping the cap and resending is guaranteed to be rate-limited again.
     * Other failures (4xx other than 429) are returned as-is — caller decides
     * whether to fall back (e.g. plain-text after Markdown 400).
     */
    <T extends BaseResponse> T executeWithRetry(BaseRequest<?, T> request, String operation) {
        T response = bot.execute(request);
        if (response.isOk() || response.errorCode() != 429) {
            return response;
        }
        Integer retryAfter = response.parameters() != null ? response.parameters().retryAfter() : null;
        if (retryAfter == null) {
            return response;
        }
        long sleepMs = retryAfter * 1000L;
        if (sleepMs > telegramRetryMaxBackoffMs) {
            log.warn("Telegram {} retry_after {}s exceeds {}ms cap; skipping retry",
                    operation, retryAfter, telegramRetryMaxBackoffMs);
            metrics.recordTelegramRetry(operation + ".skipped");
            return response;
        }
        log.warn("Telegram {} rate-limited; retry_after={}s", operation, retryAfter);
        metrics.recordTelegramRetry(operation);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return response;
        }
        return bot.execute(request);
    }

    private void sendLongReply(Update update, String message) {
        List<String> parts = TelegramUtils.splitMessage(message);
        for (String part : parts) {
            sendReply(update, part);
        }
    }

    private void sendReply(Update update, String message) {
        SendResponse sendResponse = executeWithRetry(buildSendMessage(update, message, true), "send");
        metrics.recordTelegramSend(true, sendResponse.isOk());
        if (!sendResponse.isOk()) {
            log.warn("Failed to send Markdown message: {}", sendResponse.description());
            SendResponse plainResponse = executeWithRetry(buildSendMessage(update, message, false), "send");
            metrics.recordTelegramSend(false, plainResponse.isOk());
            if (!plainResponse.isOk()) {
                log.error("Failed to send plain message: {}", plainResponse.description());
            }
        }
    }

    private SendMessage buildSendMessage(Update update, String message, boolean markdown) {
        SendMessage request = new SendMessage(update.message().chat().id(), message)
                .disableWebPagePreview(true)
                .disableNotification(true)
                .replyMarkup(new ReplyKeyboardRemove());
        if (markdown) {
            request.parseMode(ParseMode.Markdown);
        }
        if (!UpdateUtils.isPrivate(update)) {
            request.replyToMessageId(update.message().messageId());
        }
        return request;
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

        switch (text) {
            case "/start":
                presentation(update);
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
            sendReply(update, "Current model: " + current + "\nUsage: /model <name>");
            return;
        }

        String modelName = text.substring("/model".length()).trim();
        String result = gptService.setUserModel(userId, modelName);
        sendReply(update, result);
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

    private void presentation(Update update) {
        String response = gptService.sendCustomMessage(update, presentationText);
        sendReply(update, response);
    }

    private void printUsage(Update update) {
        Long userId = update.message().from().id();
        int tokens = gptService.getUserTokens(userId);
        int messages = gptService.getUserMessages(userId);
        String message = String.format("Your usage:\nTokens: %d\nMessages: %d", tokens, messages);
        sendReply(update, message);
    }

    private void resetUserContext(Update update) {
        String message = gptService.resetUserContext(update);
        sendReply(update, message);
    }
}
