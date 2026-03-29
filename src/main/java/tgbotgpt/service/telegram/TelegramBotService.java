package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.model.request.ReplyKeyboardRemove;
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

@Slf4j
@Service
public class TelegramBotService {

    private final GptService gptService;

    @Value("${bot.token}")
    private String BOT_TOKEN;
    @Value("${bot.name}")
    private String botName;
    @Value("${bot.presentation}")
    private String presentationText;

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
        String response = gptService.sendMessage(update);
        log.info("{} said ... {}", botName, response);
        sendReply(update, response);
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
        switch (update.message().text().toLowerCase()) {
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
