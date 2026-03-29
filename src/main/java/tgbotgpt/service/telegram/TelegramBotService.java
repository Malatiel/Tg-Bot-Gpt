package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.model.request.ReplyKeyboardRemove;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tgbotgpt.service.openai.GptService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class TelegramBotService {

    private static final int STREAM_UPDATE_INTERVAL_MS = 800;

    private final GptService gptService;

    @Value("${bot.token}")
    private String BOT_TOKEN;
    @Value("${bot.name}")
    private String botName;
    @Value("${bot.presentation}")
    private String presentationText;
    @Value("${bot.stream.enabled:true}")
    private boolean streamEnabled;

    private TelegramBot bot;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public TelegramBotService(GptService gptService) {
        this.gptService = gptService;
    }

    @PostConstruct
    private void init() {
        this.bot = new TelegramBot(BOT_TOKEN);
        bot.setUpdatesListener(updates -> {
            for (Update update : updates) {
                executorService.submit(() -> processUpdate(update));
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
    }

    @PreDestroy
    private void dispose() {
        log.info("Shutting down bot");
        bot.shutdown();
        executorService.shutdown();
    }

    private void processUpdate(Update update) {
        if (update.message() != null && update.message().text() != null) {
            if (update.message().text().startsWith("/")) {
                processCommand(update);
            } else {
                if (isPrivate(update) || update.message().text().toLowerCase().contains("@" + botName.toLowerCase())) {
                    processText(update);
                }
            }
        }
    }

    private void processText(Update update) {
        log.info("{} said ... {}", update.message().from().firstName(), update.message().text());

        if (streamEnabled && isPrivate(update)) {
            processTextStream(update);
        } else {
            String response = gptService.sendMessage(update);
            log.info("{} said ... {}", botName, response);
            sendReply(update, response);
        }
    }

    private void processTextStream(Update update) {
        // Send placeholder message
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
        long[] lastUpdateTime = {System.currentTimeMillis()};

        gptService.sendMessageStream(update)
                .doOnNext(chunk -> {
                    accumulated.append(chunk);
                    long now = System.currentTimeMillis();
                    if (now - lastUpdateTime[0] >= STREAM_UPDATE_INTERVAL_MS) {
                        String current = accumulated.toString();
                        if (!current.equals(lastSent.get())) {
                            editMessage(chatId, messageId, current);
                            lastSent.set(current);
                            lastUpdateTime[0] = now;
                        }
                    }
                })
                .doOnComplete(() -> {
                    String finalText = accumulated.toString();
                    if (!finalText.isEmpty() && !finalText.equals(lastSent.get())) {
                        editMessage(chatId, messageId, finalText);
                    } else if (finalText.isEmpty()) {
                        editMessage(chatId, messageId, "No response received.");
                    }
                    log.info("{} said ... {}", botName, finalText);
                })
                .doOnError(e -> {
                    log.error("Stream error: ", e);
                    editMessage(chatId, messageId, "Sorry, something went wrong.");
                })
                .blockLast();
    }

    private void editMessage(long chatId, int messageId, String text) {
        try {
            bot.execute(new EditMessageText(chatId, messageId, text));
        } catch (Exception e) {
            log.error("Failed to edit message: ", e);
        }
    }

    private void sendReply(Update update, String message) {
        SendMessage request = new SendMessage(update.message().chat().id(), message)
                .parseMode(ParseMode.Markdown)
                .disableWebPagePreview(true)
                .disableNotification(true)
                .replyMarkup(new ReplyKeyboardRemove());
        if (!isPrivate(update)) {
            request.replyToMessageId(update.message().messageId());
        }
        SendResponse sendResponse = bot.execute(request);
        if (!sendResponse.isOk()) {
            log.error(sendResponse.message().toString());
        }
    }

    private boolean isPrivate(Update update) {
        return update.message().chat().type().equals(Chat.Type.Private);
    }

    private void processCommand(Update update) {
        String text = update.message().text().toLowerCase();

        if (text.startsWith("/model")) {
            handleModelCommand(update);
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
                log.warn("Unknown command: {}", update.message().text());
        }
    }

    private void handleModelCommand(Update update) {
        String text = update.message().text().trim();
        Long userId = update.message().from().id();

        if (text.equals("/model")) {
            String current = gptService.getUserModel(userId);
            sendReply(update, "Current model: " + current + "\nUsage: /model <name>");
            return;
        }

        String modelName = text.substring("/model".length()).trim();
        String result = gptService.setUserModel(userId, modelName);
        sendReply(update, result);
    }

    private void presentation(Update update) {
        String response = gptService.sendCustomMessage(update, presentationText);
        sendReply(update, response);
    }

    private void printUsage(Update update) {
        String message = String.format("Token count: %d", gptService.getNumTokens());
        sendReply(update, message);
    }

    private void resetUserContext(Update update) {
        String message = gptService.resetUserContext(update);
        sendReply(update, message);
    }
}
