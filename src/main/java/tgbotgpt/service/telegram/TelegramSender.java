package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.model.request.ReplyKeyboardRemove;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.SendResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tgbotgpt.service.BotMetricsService;
import tgbotgpt.utils.TelegramUtils;
import tgbotgpt.utils.UpdateUtils;

import java.util.List;

@Slf4j
@Component
class TelegramSender {

    private final BotMetricsService metrics;

    TelegramSender(BotMetricsService metrics) {
        this.metrics = metrics;
    }

    void sendLongReply(TelegramBot bot, Update update, String message, long retryMaxBackoffMs) {
        List<String> parts = TelegramUtils.splitMessage(message);
        for (String part : parts) {
            sendReply(bot, update, part, retryMaxBackoffMs);
        }
    }

    void sendReply(TelegramBot bot, Update update, String message, long retryMaxBackoffMs) {
        SendResponse sendResponse = executeWithRetry(bot, buildSendMessage(update, message, true), "send", retryMaxBackoffMs);
        metrics.recordTelegramSend(true, sendResponse.isOk());
        if (!sendResponse.isOk()) {
            log.warn("Failed to send Markdown message: {}", sendResponse.description());
            SendResponse plainResponse = executeWithRetry(bot, buildSendMessage(update, message, false), "send", retryMaxBackoffMs);
            metrics.recordTelegramSend(false, plainResponse.isOk());
            if (!plainResponse.isOk()) {
                log.error("Failed to send plain message: {}", plainResponse.description());
            }
        }
    }

    boolean sendMessage(TelegramBot bot, long chatId, String message, long retryMaxBackoffMs) {
        SendResponse sendResponse = executeWithRetry(
                bot, buildSendMessage(chatId, message, true), "send", retryMaxBackoffMs);
        metrics.recordTelegramSend(true, sendResponse.isOk());
        if (sendResponse.isOk()) {
            return true;
        }

        log.warn("Failed to send proactive Markdown message: {}", sendResponse.description());
        SendResponse plainResponse = executeWithRetry(
                bot, buildSendMessage(chatId, message, false), "send", retryMaxBackoffMs);
        metrics.recordTelegramSend(false, plainResponse.isOk());
        if (!plainResponse.isOk()) {
            log.error("Failed to send proactive plain message: {}", plainResponse.description());
        }
        return plainResponse.isOk();
    }

    void editMessage(TelegramBot bot, long chatId, int messageId, String text, long retryMaxBackoffMs) {
        try {
            BaseResponse response = executeWithRetry(
                    bot,
                    new EditMessageText(chatId, messageId, TelegramUtils.truncateForEdit(text)),
                    "edit",
                    retryMaxBackoffMs);
            metrics.recordTelegramEdit(response.isOk());
        } catch (Exception e) {
            metrics.recordTelegramEdit(false);
            log.error("Failed to edit message: ", e);
        }
    }

    <T extends BaseResponse> T executeWithRetry(
            TelegramBot bot,
            BaseRequest<?, T> request,
            String operation,
            long retryMaxBackoffMs
    ) {
        T response = bot.execute(request);
        if (response.isOk() || response.errorCode() != 429) {
            return response;
        }
        Integer retryAfter = response.parameters() != null ? response.parameters().retryAfter() : null;
        if (retryAfter == null) {
            return response;
        }
        long sleepMs = retryAfter * 1000L;
        if (sleepMs > retryMaxBackoffMs) {
            log.warn("Telegram {} retry_after {}s exceeds {}ms cap; skipping retry",
                    operation, retryAfter, retryMaxBackoffMs);
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

    private SendMessage buildSendMessage(long chatId, String message, boolean markdown) {
        SendMessage request = new SendMessage(chatId, message)
                .disableWebPagePreview(true);
        if (markdown) {
            request.parseMode(ParseMode.Markdown);
        }
        return request;
    }
}
