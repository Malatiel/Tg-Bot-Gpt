package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tgbotgpt.service.BotAdminService;
import tgbotgpt.service.BotMetricsService;
import tgbotgpt.service.DocumentService;
import tgbotgpt.service.ImageService;
import tgbotgpt.service.openai.GptService;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramBotServiceTest {

    @Test
    void handleUpdatesReturnsLastSuccessfullySubmittedUpdateId() throws InterruptedException {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2)
        );
        // Saturate: 1 worker stuck, 2 queue slots filled — next submit will be rejected.
        executor.submit(() -> { try { hold.await(); } catch (InterruptedException ignored) { } });
        executor.submit(() -> { });
        executor.submit(() -> { });

        BotMetricsService metrics = mock(BotMetricsService.class);
        TelegramBotService service = newService(metrics);
        ReflectionTestUtils.setField(service, "executorService", executor);

        try {
            int confirmed = service.handleUpdates(List.of(update(100), update(101)));

            assertEquals(UpdatesListener.CONFIRMED_UPDATES_NONE, confirmed);
            verify(metrics).recordExecutorRejection();
        } finally {
            hold.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void handleUpdatesAdvancesOffsetForAcceptedSubmits() throws InterruptedException {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2)
        );
        // Saturate worker, leave both queue slots free.
        executor.submit(() -> { try { hold.await(); } catch (InterruptedException ignored) { } });

        BotMetricsService metrics = mock(BotMetricsService.class);
        TelegramBotService service = newService(metrics);
        ReflectionTestUtils.setField(service, "executorService", executor);

        try {
            int confirmed = service.handleUpdates(List.of(update(200), update(201), update(202)));

            // Two slots free → updates 200, 201 accepted; 202 rejected.
            assertEquals(201, confirmed);
            verify(metrics).recordExecutorRejection();
        } finally {
            hold.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void executeWithRetrySleepsThenRetriesOn429WithRetryAfter() {
        TelegramBot bot = mock(TelegramBot.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        SendMessage request = new SendMessage(1L, "hi");
        SendResponse rateLimited = sendResponseWith(429, 1);
        SendResponse ok = sendResponseWith(0, null);
        when(bot.execute(eq(request))).thenReturn(rateLimited).thenReturn(ok);

        TelegramBotService service = newService(metrics);
        ReflectionTestUtils.setField(service, "bot", bot);
        ReflectionTestUtils.setField(service, "telegramRetryMaxBackoffMs", 5000L);

        SendResponse response = service.executeWithRetry(request, "send");

        assertEquals(0, response.errorCode());
        verify(bot, times(2)).execute(eq(request));
        verify(metrics).recordTelegramRetry("send");
    }

    @Test
    void executeWithRetrySkipsRetryWhenRetryAfterExceedsCap() {
        TelegramBot bot = mock(TelegramBot.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        SendMessage request = new SendMessage(1L, "hi");
        SendResponse rateLimited = sendResponseWith(429, 60);
        when(bot.execute(eq(request))).thenReturn(rateLimited);

        TelegramBotService service = newService(metrics);
        ReflectionTestUtils.setField(service, "bot", bot);
        ReflectionTestUtils.setField(service, "telegramRetryMaxBackoffMs", 5000L);

        SendResponse response = service.executeWithRetry(request, "send");

        assertEquals(429, response.errorCode());
        verify(bot, times(1)).execute(eq(request));
        verify(metrics).recordTelegramRetry("send.skipped");
    }

    @Test
    void disposeDrainsExecutorBeforeClosingTelegramClient() throws Exception {
        TelegramBot bot = mock(TelegramBot.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)
        );

        java.util.concurrent.atomic.AtomicLong taskFinishedAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong botShutdownAt = new java.util.concurrent.atomic.AtomicLong();
        org.mockito.Mockito.doAnswer(inv -> {
            botShutdownAt.set(System.nanoTime());
            return null;
        }).when(bot).shutdown();

        executor.submit(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            taskFinishedAt.set(System.nanoTime());
        });

        TelegramBotService service = newService(metrics);
        ReflectionTestUtils.setField(service, "bot", bot);
        ReflectionTestUtils.setField(service, "executorService", executor);
        ReflectionTestUtils.setField(service, "shutdownTimeoutSeconds", 5);

        java.lang.reflect.Method dispose = TelegramBotService.class.getDeclaredMethod("dispose");
        dispose.setAccessible(true);
        dispose.invoke(service);

        assertTrue(taskFinishedAt.get() > 0, "in-flight task should have finished");
        assertTrue(botShutdownAt.get() > 0, "bot.shutdown() should have been called");
        assertTrue(botShutdownAt.get() > taskFinishedAt.get(),
                "bot.shutdown() must run AFTER in-flight tasks finish");
    }

    @Test
    void executeWithRetryDoesNotRetryNon429Failures() {
        TelegramBot bot = mock(TelegramBot.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        SendMessage request = new SendMessage(1L, "hi");
        SendResponse badRequest = sendResponseWith(400, null);
        when(bot.execute(eq(request))).thenReturn(badRequest);

        TelegramBotService service = newService(metrics);
        ReflectionTestUtils.setField(service, "bot", bot);
        ReflectionTestUtils.setField(service, "telegramRetryMaxBackoffMs", 5000L);

        SendResponse response = service.executeWithRetry(request, "send");

        assertEquals(400, response.errorCode());
        verify(bot, times(1)).execute(eq(request));
    }

    private TelegramBotService newService(BotMetricsService metrics) {
        return new TelegramBotService(
                mock(GptService.class),
                mock(ImageService.class),
                mock(DocumentService.class),
                mock(BotAdminService.class),
                metrics
        );
    }

    private Update update(int id) {
        Update update = mock(Update.class);
        when(update.updateId()).thenReturn(id);
        return update;
    }

    private SendResponse sendResponseWith(int errorCode, Integer retryAfter) {
        SendResponse response = mock(SendResponse.class);
        when(response.isOk()).thenReturn(errorCode == 0);
        when(response.errorCode()).thenReturn(errorCode);
        if (retryAfter != null) {
            com.pengrad.telegrambot.model.ResponseParameters params =
                    mock(com.pengrad.telegrambot.model.ResponseParameters.class);
            when(params.retryAfter()).thenReturn(retryAfter);
            when(response.parameters()).thenReturn(params);
        }
        return response;
    }
}
