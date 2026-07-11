package tgbotgpt.service;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.PreCheckoutQuery;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.SendInvoice;
import com.pengrad.telegrambot.response.SendResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import tgbotgpt.model.entity.BotUserPayment;
import tgbotgpt.repository.BotUserPaymentRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StarsPaymentServiceTest {

    @Test
    void sendInvoiceBuildsTelegramStarsInvoice() {
        TelegramBot bot = mock(TelegramBot.class);
        SendResponse response = mock(SendResponse.class);
        doReturn(response).when(bot).execute(any(SendInvoice.class));
        StarsPaymentService service = newService();

        SendResponse result = service.sendInvoice(bot, 100L, 1L);

        assertEquals(response, result);
        ArgumentCaptor<SendInvoice> captor = ArgumentCaptor.forClass(SendInvoice.class);
        verify(bot).execute(captor.capture());
        assertEquals(100L, captor.getValue().getParameters().get("chat_id"));
        assertEquals("XTR", captor.getValue().getParameters().get("currency"));
        assertEquals("pro:1", captor.getValue().getParameters().get("payload"));
    }

    @Test
    void validatePreCheckoutAcceptsExpectedStarsPayment() {
        StarsPaymentService service = newService();

        Optional<String> error = service.validatePreCheckout(preCheckoutQuery(1L, "XTR", 100, "pro:1"));

        assertTrue(error.isEmpty());
    }

    @Test
    void validatePreCheckoutRejectsMismatchedAmount() {
        StarsPaymentService service = newService();

        Optional<String> error = service.validatePreCheckout(preCheckoutQuery(1L, "XTR", 99, "pro:1"));

        assertTrue(error.isPresent());
        assertTrue(error.get().contains("amount"));
    }

    @Test
    void processPaymentRecordsChargeAndActivatesPro() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        when(paymentRepository.existsByTelegramChargeId("charge-1")).thenReturn(false);
        when(paymentRepository.saveAndFlush(any(BotUserPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userSettingsService.getOrCreateUser(1L, null, null)).thenReturn(new tgbotgpt.model.entity.BotUser(1L, null, null));
        when(userSettingsService.activatePaidProPlan(1L, 30)).thenReturn(true);
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.PaymentResult result =
                service.processPayment(1L, "charge-1", "XTR", 100, "pro:1");

        assertTrue(result.notifyOwners());
        assertTrue(result.userMessage().contains("PRO is active"));
        ArgumentCaptor<BotUserPayment> captor = ArgumentCaptor.forClass(BotUserPayment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());
        assertEquals(1L, captor.getValue().getTelegramId());
        assertEquals("charge-1", captor.getValue().getTelegramChargeId());
        assertEquals(100, captor.getValue().getStarsAmount());
        verify(userSettingsService).activatePaidProPlan(1L, 30);
        var inOrder = inOrder(userSettingsService, paymentRepository);
        inOrder.verify(userSettingsService).getOrCreateUser(1L, null, null);
        inOrder.verify(paymentRepository).saveAndFlush(any(BotUserPayment.class));
        inOrder.verify(userSettingsService).activatePaidProPlan(1L, 30);
    }

    @Test
    void processPaymentSkipsAlreadyKnownCharge() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        when(paymentRepository.existsByTelegramChargeId("charge-1")).thenReturn(true);
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.PaymentResult result =
                service.processPayment(1L, "charge-1", "XTR", 100, "pro:1");

        assertTrue(result.duplicate());
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(userSettingsService, never()).getOrCreateUser(anyLong(), any(), any());
        verify(userSettingsService, never()).activatePaidProPlan(anyLong(), anyInt());
    }

    @Test
    void processPaymentTreatsConcurrentUniqueViolationAsDuplicate() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        when(paymentRepository.existsByTelegramChargeId("charge-1")).thenReturn(false);
        when(userSettingsService.getOrCreateUser(1L, null, null)).thenReturn(new tgbotgpt.model.entity.BotUser(1L, null, null));
        when(paymentRepository.saveAndFlush(any(BotUserPayment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.PaymentResult result =
                service.processPayment(1L, "charge-1", "XTR", 100, "pro:1");

        assertTrue(result.duplicate());
        verify(userSettingsService).getOrCreateUser(1L, null, null);
        verify(userSettingsService, never()).activatePaidProPlan(anyLong(), anyInt());
    }

    @Test
    void processPaymentRejectsInvalidPayloadBeforeWriting() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.PaymentResult result =
                service.processPayment(1L, "charge-1", "XTR", 100, "pro:2");

        assertTrue(result.notifyOwners());
        assertTrue(result.userMessage().contains("did not match"));
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(userSettingsService, never()).activatePaidProPlan(anyLong(), anyInt());
    }

    @Test
    void processRefundMarksPaymentRefundedAndDowngradesPlan() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        BotUserPayment payment = new BotUserPayment(1L, "charge-1", 100);
        when(paymentRepository.findByTelegramChargeId("charge-1")).thenReturn(Optional.of(payment));
        when(userSettingsService.getUsageStatus(1L, false)).thenReturn(usageStatus("pro"));
        when(userSettingsService.downgradeToFree(1L)).thenReturn(true);
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.RefundResult result = service.processRefund(1L, "charge-1");

        assertTrue(result.notifyOwners());
        assertTrue(result.userMessage().contains("plan is Free"));
        assertEquals("refunded", payment.getStatus());
        verify(userSettingsService).downgradeToFree(1L);
        verify(paymentRepository).saveAndFlush(payment);
    }

    @Test
    void processRefundSkipsAlreadyRefundedPayment() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        BotUserPayment payment = new BotUserPayment(1L, "charge-1", 100);
        payment.setStatus("refunded");
        when(paymentRepository.findByTelegramChargeId("charge-1")).thenReturn(Optional.of(payment));
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.RefundResult result = service.processRefund(1L, "charge-1");

        assertTrue(result.duplicate());
        verify(userSettingsService, never()).downgradeToFree(anyLong());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void processRefundPreservesPlanWhenAnotherCompletedPaymentExists() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        BotUserPayment payment = new BotUserPayment(1L, "charge-1", 100);
        when(paymentRepository.findByTelegramChargeId("charge-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.existsByTelegramIdAndStatusAndTelegramChargeIdNot(1L, "completed", "charge-1"))
                .thenReturn(true);
        when(userSettingsService.getUsageStatus(1L, false)).thenReturn(usageStatus("pro"));
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.RefundResult result = service.processRefund(1L, "charge-1");

        assertTrue(result.userMessage().contains("plan is pro"));
        assertEquals("refunded", payment.getStatus());
        verify(userSettingsService, never()).downgradeToFree(anyLong());
        verify(paymentRepository).saveAndFlush(payment);
    }

    @Test
    void processRefundPreservesOwnerPlan() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        BotUserPayment payment = new BotUserPayment(1L, "charge-1", 100);
        when(paymentRepository.findByTelegramChargeId("charge-1")).thenReturn(Optional.of(payment));
        when(userSettingsService.getUsageStatus(1L, false)).thenReturn(usageStatus("owner"));
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.RefundResult result = service.processRefund(1L, "charge-1");

        assertTrue(result.userMessage().contains("plan is owner"));
        assertEquals("refunded", payment.getStatus());
        verify(userSettingsService, never()).downgradeToFree(anyLong());
    }

    @Test
    void processRefundReportsUnknownChargeWithoutChangingPlan() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        when(paymentRepository.findByTelegramChargeId("unknown")).thenReturn(Optional.empty());
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.RefundResult result = service.processRefund(1L, "unknown");

        assertTrue(result.notifyOwners());
        assertTrue(result.ownerMessage().contains("no matching payment"));
        verify(userSettingsService, never()).downgradeToFree(anyLong());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void processRefundRejectsPaymentOwnedByAnotherUser() {
        BotUserPaymentRepository paymentRepository = mock(BotUserPaymentRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        BotUserPayment payment = new BotUserPayment(2L, "charge-1", 100);
        when(paymentRepository.findByTelegramChargeId("charge-1")).thenReturn(Optional.of(payment));
        StarsPaymentService service = newService(paymentRepository, userSettingsService);

        StarsPaymentService.RefundResult result = service.processRefund(1L, "charge-1");

        assertTrue(result.notifyOwners());
        assertTrue(result.ownerMessage().contains("user mismatch"));
        verify(userSettingsService, never()).downgradeToFree(anyLong());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    private StarsPaymentService newService() {
        return newService(mock(BotUserPaymentRepository.class), mock(UserSettingsService.class));
    }

    private StarsPaymentService newService(BotUserPaymentRepository paymentRepository,
                                           UserSettingsService userSettingsService) {
        StarsPaymentService service = new StarsPaymentService(paymentRepository, userSettingsService);
        ReflectionTestUtils.setField(service, "starsPrice", 100);
        ReflectionTestUtils.setField(service, "proDefaultDays", 30);
        return service;
    }

    private UserSettingsService.UsageStatus usageStatus(String plan) {
        return new UserSettingsService.UsageStatus(plan, "2026-07", 0, 0, 0, 0, 0, 0, null);
    }

    private PreCheckoutQuery preCheckoutQuery(Long userId, String currency, Integer amount, String payload) {
        PreCheckoutQuery query = mock(PreCheckoutQuery.class);
        User user = mock(User.class);
        when(query.from()).thenReturn(user);
        when(user.id()).thenReturn(userId);
        when(query.currency()).thenReturn(currency);
        when(query.totalAmount()).thenReturn(amount);
        when(query.invoicePayload()).thenReturn(payload);
        return query;
    }
}
