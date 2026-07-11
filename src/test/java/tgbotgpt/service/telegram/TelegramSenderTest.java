package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tgbotgpt.service.BotMetricsService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramSenderTest {

    @Test
    void proactiveSendReturnsTrueOnMarkdownSuccess() {
        TelegramBot bot = mock(TelegramBot.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        SendResponse response = response(true);
        when(bot.execute(any(SendMessage.class))).thenReturn(response);

        boolean sent = new TelegramSender(metrics).sendMessage(bot, 42L, "message", 5000L);

        assertTrue(sent);
        ArgumentCaptor<SendMessage> request = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(request.capture());
        assertNull(request.getValue().getParameters().get("disable_notification"));
        assertNull(request.getValue().getParameters().get("reply_markup"));
        verify(metrics).recordTelegramSend(true, true);
    }

    @Test
    void proactiveSendReturnsTrueWhenPlainFallbackSucceeds() {
        TelegramBot bot = mock(TelegramBot.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        SendResponse markdownFailure = response(false);
        SendResponse plainSuccess = response(true);
        when(bot.execute(any(SendMessage.class))).thenReturn(markdownFailure, plainSuccess);

        boolean sent = new TelegramSender(metrics).sendMessage(bot, 42L, "message", 5000L);

        assertTrue(sent);
        verify(bot, times(2)).execute(any(SendMessage.class));
        verify(metrics).recordTelegramSend(true, false);
        verify(metrics).recordTelegramSend(false, true);
    }

    @Test
    void proactiveSendFallsBackToPlainAndReturnsFalseWhenBothAttemptsFail() {
        TelegramBot bot = mock(TelegramBot.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        SendResponse response = response(false);
        when(bot.execute(any(SendMessage.class))).thenReturn(response);

        boolean sent = new TelegramSender(metrics).sendMessage(bot, 42L, "message", 5000L);

        assertFalse(sent);
        verify(bot, times(2)).execute(any(SendMessage.class));
        verify(metrics).recordTelegramSend(true, false);
        verify(metrics).recordTelegramSend(false, false);
    }

    private SendResponse response(boolean ok) {
        SendResponse response = mock(SendResponse.class);
        when(response.isOk()).thenReturn(ok);
        when(response.errorCode()).thenReturn(ok ? 0 : 400);
        return response;
    }
}
