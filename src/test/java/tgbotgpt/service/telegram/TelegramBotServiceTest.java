package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.SendResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import tgbotgpt.service.BotAdminService;
import tgbotgpt.service.BotMetricsService;
import tgbotgpt.service.DocumentService;
import tgbotgpt.service.ImageService;
import tgbotgpt.service.openai.GptService;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void settingsCommandSendsSettingsSummary() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.getSettingsSummary(1L)).thenReturn("Settings\nModel: gpt-5.4-nano");
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/settings"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        assertEquals("Settings\nModel: gpt-5.4-nano", captor.getValue().getParameters().get("text"));
    }

    @Test
    void modelCommandSendsInlineModelKeyboard() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.getUserModel(1L)).thenReturn("gpt-5.4-nano");
        when(gptService.getAvailableModels()).thenReturn("gpt-5.4-nano, gpt-5.4-mini");
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/model"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        assertTrue(((String) captor.getValue().getParameters().get("text")).contains("Current model: gpt-5.4-nano"));
        assertInstanceOf(InlineKeyboardMarkup.class, captor.getValue().getParameters().get("reply_markup"));
    }

    @Test
    void balanceCommandSendsBalanceSummary() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.getBalanceSummary(1L)).thenReturn("Balance\nPlan: FREE");
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/balance"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        assertEquals("Balance\nPlan: FREE", captor.getValue().getParameters().get("text"));
    }

    @Test
    void planCommandSendsInlinePlanKeyboard() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.getPlanSummary(1L)).thenReturn("Current plan: FREE");
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/plan"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        assertEquals("Current plan: FREE", captor.getValue().getParameters().get("text"));
        assertInstanceOf(InlineKeyboardMarkup.class, captor.getValue().getParameters().get("reply_markup"));
    }

    @Test
    void startCommandSendsLocalWelcomeWithoutCallingOpenAi() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/start"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        String text = (String) captor.getValue().getParameters().get("text");
        assertTrue(text.contains("Hello! I am GPTbot in Telegram."));
        assertTrue(text.contains("/help"));
        verify(gptService, never()).sendCustomMessage(any(), any());
    }

    @Test
    void helpCommandSendsLocalCommandMap() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);
        ReflectionTestUtils.setField(service, "botName", "IndividualBotJava");

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/help"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        String text = (String) captor.getValue().getParameters().get("text");
        assertTrue(text.contains("GPTbot help"));
        assertTrue(text.contains("Mention @IndividualBotJava"));
        assertTrue(text.contains("/examples"));
        assertTrue(text.contains("/settings"));
        assertTrue(text.contains("/upgrade"));
    }

    @Test
    void examplesCommandSendsLocalPromptExamplesWithoutCallingOpenAi() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/examples"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        String text = (String) captor.getValue().getParameters().get("text");
        assertTrue(text.contains("GPTbot examples"));
        assertTrue(text.contains("Document caption:"));
        assertTrue(text.contains("Image caption:"));
        assertTrue(text.contains("/balance"));
        verify(gptService, never()).sendCustomMessage(any(), any());
    }

    @Test
    void adminPlanCommandDelegatesToGptService() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.setUserBillingPlan(1L, 2L, "pro")).thenReturn("Plan for 2 set to: pro");
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/admin plan 2 pro"));

        verify(gptService).setUserBillingPlan(1L, 2L, "pro");
    }

    @Test
    void adminApproveCommandDelegatesToGptService() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.approveUserPro(1L, 2L, 30)).thenReturn("Plan for 2 set to: pro until 2026-05-30 12:00");
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/admin approve 2 30d"));

        verify(gptService).approveUserPro(1L, 2L, 30);
    }

    @Test
    void adminExtendAndDowngradeCommandsDelegateToGptService() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.extendUserPro(1L, 2L, 15)).thenReturn("Plan for 2 set to: pro until 2026-06-14 12:00");
        when(gptService.downgradeUser(1L, 2L)).thenReturn("Plan for 2 set to: free");
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/admin extend 2 15d"));
        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/admin downgrade 2"));

        verify(gptService).extendUserPro(1L, 2L, 15);
        verify(gptService).downgradeUser(1L, 2L);
    }

    @Test
    void upgradeCommandRepliesAndNotifiesOwners() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotAdminService adminService = mock(BotAdminService.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.createUpgradeRequest(1L)).thenReturn(new GptService.UpgradeRequest(
                "Upgrade request sent.",
                "Approve Pro: /admin approve 1 30d",
                true
        ));
        when(adminService.getOwnerIds()).thenReturn(Set.of(99L));
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, adminService, metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/upgrade"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot, times(2)).execute(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(request -> "Upgrade request sent.".equals(request.getParameters().get("text"))));
        assertTrue(captor.getAllValues().stream()
                .anyMatch(request -> "Approve Pro: /admin approve 1 30d".equals(request.getParameters().get("text"))));
    }

    @Test
    void streamingErrorFallsBackToNonStreamCompletion() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        BotMetricsService metrics = mock(BotMetricsService.class);
        Update update = textUpdate(1L, "Hello");
        when(gptService.sendMessageStream(update)).thenReturn(Flux.error(new RuntimeException("stream failed")));
        when(gptService.sendMessageAfterStreamFailure(update)).thenReturn("Fallback answer");
        when(gptService.isOpenAiQuotaOrRateLimitIssue("Fallback answer")).thenReturn(false);
        doReturn(sendResponseWithMessage(10)).when(bot).execute(any(SendMessage.class));
        BaseResponse editResponse = mock(BaseResponse.class);
        when(editResponse.isOk()).thenReturn(true);
        doReturn(editResponse).when(bot).execute(any(EditMessageText.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);
        ReflectionTestUtils.setField(service, "streamEnabled", true);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", update);

        verify(gptService).sendMessageAfterStreamFailure(update);
        verify(bot).execute(any(EditMessageText.class));
    }

    @Test
    void unauthorizedUserIsDeniedAndCommandIsNotExecuted() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(false);
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/settings"));

        verify(gptService, never()).getSettingsSummary(anyLong());
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        assertTrue(((String) captor.getValue().getParameters().get("text")).contains("not in the access list"));
    }

    @Test
    void processUpdateRecordsFailureWhenCommandThrows() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(true);
        when(gptService.getSettingsSummary(1L)).thenThrow(new IllegalStateException("boom"));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdate(1L, "/settings")));

        verify(metrics).recordOperationDuration(eq("command"), eq(false), any(Duration.class));
    }

    @Test
    void messageWithoutSenderIsDeniedWithoutExecutingCommand() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.isAllowed(isNull(), isNull(), isNull())).thenReturn(false);
        doReturn(sendResponseWith(0, null)).when(bot).execute(any(SendMessage.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "processUpdate", textUpdateWithoutSender("/settings")));

        verify(gptService).isAllowed(isNull(), isNull(), isNull());
        verify(gptService, never()).getSettingsSummary(anyLong());
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        assertTrue(((String) captor.getValue().getParameters().get("text")).contains("not in the access list"));
        verify(metrics).recordOperationDuration(eq("command"), eq(true), any(Duration.class));
    }

    @Test
    void unauthorizedCallbackIsAnsweredAndNotExecuted() {
        TelegramBot bot = mock(TelegramBot.class);
        GptService gptService = mock(GptService.class);
        BotMetricsService metrics = mock(BotMetricsService.class);
        when(gptService.isAllowed(anyLong(), any(), any())).thenReturn(false);
        BaseResponse callbackResponse = mock(BaseResponse.class);
        when(callbackResponse.isOk()).thenReturn(true);
        doReturn(callbackResponse).when(bot).execute(any(AnswerCallbackQuery.class));

        TelegramBotService service = newService(gptService, mock(BotAdminService.class), metrics);
        ReflectionTestUtils.setField(service, "bot", bot);

        ReflectionTestUtils.invokeMethod(service, "processUpdate", callbackUpdate("model:gpt-5.4-mini"));

        verify(bot).execute(any(AnswerCallbackQuery.class));
        verify(gptService, never()).setUserModel(anyLong(), any());
        verify(metrics).recordOperationDuration(eq("callback"), eq(true), any(Duration.class));
    }

    private TelegramBotService newService(BotMetricsService metrics) {
        return newService(mock(GptService.class), mock(BotAdminService.class), metrics);
    }

    private TelegramBotService newService(GptService gptService, BotAdminService adminService, BotMetricsService metrics) {
        return new TelegramBotService(
                gptService,
                mock(ImageService.class),
                mock(DocumentService.class),
                adminService,
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

    private SendResponse sendResponseWithMessage(int messageId) {
        SendResponse response = sendResponseWith(0, null);
        Message message = mock(Message.class);
        when(message.messageId()).thenReturn(messageId);
        when(response.message()).thenReturn(message);
        return response;
    }

    private Update textUpdate(Long userId, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        Chat chat = mock(Chat.class);

        when(update.message()).thenReturn(message);
        when(message.text()).thenReturn(text);
        when(message.from()).thenReturn(user);
        when(message.chat()).thenReturn(chat);
        when(message.messageId()).thenReturn(42);
        when(user.id()).thenReturn(userId);
        when(user.firstName()).thenReturn("Test");
        when(user.username()).thenReturn("test");
        when(chat.id()).thenReturn(100L);
        when(chat.type()).thenReturn(Chat.Type.Private);

        return update;
    }

    private Update callbackUpdate(String data) {
        Update update = mock(Update.class);
        CallbackQuery callback = mock(CallbackQuery.class);
        User user = mock(User.class);
        Message message = mock(Message.class);
        Chat chat = mock(Chat.class);

        when(update.callbackQuery()).thenReturn(callback);
        when(callback.id()).thenReturn("callback-id");
        when(callback.data()).thenReturn(data);
        when(callback.from()).thenReturn(user);
        when(callback.message()).thenReturn(message);
        when(user.id()).thenReturn(1L);
        when(user.username()).thenReturn("test");
        when(message.chat()).thenReturn(chat);
        when(chat.title()).thenReturn("group");

        return update;
    }

    private Update textUpdateWithoutSender(String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        Chat chat = mock(Chat.class);

        when(update.message()).thenReturn(message);
        when(message.text()).thenReturn(text);
        when(message.chat()).thenReturn(chat);
        when(message.messageId()).thenReturn(42);
        when(chat.id()).thenReturn(100L);
        when(chat.type()).thenReturn(Chat.Type.Private);

        return update;
    }
}
