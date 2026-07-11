package tgbotgpt.service;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.PreCheckoutQuery;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.LabeledPrice;
import com.pengrad.telegrambot.request.SendInvoice;
import com.pengrad.telegrambot.response.SendResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tgbotgpt.model.entity.BotUserPayment;
import tgbotgpt.repository.BotUserPaymentRepository;
import tgbotgpt.utils.LogUtils;

import java.util.Optional;

@Slf4j
@Service
public class StarsPaymentService {

    private static final String CURRENCY = "XTR";
    private static final String PAYLOAD_PREFIX = "pro:";

    private final BotUserPaymentRepository paymentRepository;
    private final UserSettingsService userSettingsService;

    @Value("${billing.pro.stars.price:100}")
    private int starsPrice;

    @Value("${billing.pro.default.days:30}")
    private int proDefaultDays;

    public StarsPaymentService(BotUserPaymentRepository paymentRepository, UserSettingsService userSettingsService) {
        this.paymentRepository = paymentRepository;
        this.userSettingsService = userSettingsService;
    }

    public SendResponse sendInvoice(TelegramBot bot, long chatId, long userId) {
        return bot.execute(buildInvoice(chatId, userId));
    }

    public String buyButtonText() {
        return "Buy PRO ⭐ " + starsPrice + " Stars";
    }

    SendInvoice buildInvoice(long chatId, long userId) {
        return new SendInvoice(
                chatId,
                "GPTbot PRO",
                "PRO access for %d days".formatted(proDefaultDays),
                payloadFor(userId),
                CURRENCY,
                new LabeledPrice("PRO %d days".formatted(proDefaultDays), starsPrice)
        ).disableNotification(true);
    }

    public Optional<String> validatePreCheckout(PreCheckoutQuery query) {
        if (query == null) {
            return Optional.of("Payment request is invalid.");
        }
        User from = query.from();
        Long userId = from != null ? from.id() : null;
        return validatePaymentFields(userId, query.currency(), query.totalAmount(), query.invoicePayload());
    }

    public PaymentResult processPayment(long userId, String chargeId, String currency, Integer starsAmount, String payload) {
        Optional<String> validationError = validatePaymentFields(userId, currency, starsAmount, payload);
        if (validationError.isPresent()) {
            return PaymentResult.failed(
                    "Payment received, but it did not match the expected PRO invoice. Owner has been notified.",
                    "Invalid Stars payment for user %d: %s".formatted(userId, validationError.get())
            );
        }
        if (chargeId == null || chargeId.isBlank()) {
            return PaymentResult.failed(
                    "Payment received, but Telegram charge id is missing. Owner has been notified.",
                    "Stars payment for user %d has no telegram charge id".formatted(userId)
            );
        }
        if (paymentRepository.existsByTelegramChargeId(chargeId)) {
            return PaymentResult.duplicate("Payment already processed. Your PRO access is active.");
        }

        userSettingsService.getOrCreateUser(userId, null, null);
        try {
            paymentRepository.saveAndFlush(new BotUserPayment(userId, chargeId, starsAmount));
        } catch (DataIntegrityViolationException duplicate) {
            // UNIQUE telegram_charge_id race: another worker processed the same charge first.
            log.info("Duplicate Stars payment charge {} for user {}",
                    chargeId, LogUtils.hashUserId(userId));
            return PaymentResult.duplicate("Payment already processed. Your PRO access is active.");
        }

        boolean activated = userSettingsService.activatePaidProPlan(userId, proDefaultDays);
        if (!activated) {
            return PaymentResult.failed(
                    "Payment received, but PRO activation failed. Owner has been notified.",
                    "Stars payment activation failed for user %d, charge %s, amount %d"
                            .formatted(userId, chargeId, starsAmount)
            );
        }

        return PaymentResult.completed(
                "Payment received. PRO is active for %d days.".formatted(proDefaultDays),
                "Stars PRO purchase completed\nUser: %d\nAmount: %d Stars\nCharge: %s"
                        .formatted(userId, starsAmount, chargeId)
        );
    }

    @Transactional
    public RefundResult processRefund(Long userId, String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            return RefundResult.failed(
                    "Refund received, but Telegram charge id is missing. Owner has been notified.",
                    "Stars refund for user %d has no telegram charge id".formatted(userId)
            );
        }

        Optional<BotUserPayment> paymentOptional = paymentRepository.findByTelegramChargeId(chargeId);
        if (paymentOptional.isEmpty()) {
            return RefundResult.failed(
                    "Refund received, but the original payment was not found. Owner has been notified.",
                    "Stars refund has no matching payment\nUser: %d\nCharge: %s".formatted(userId, chargeId)
            );
        }

        BotUserPayment payment = paymentOptional.get();
        if (userId != null && !payment.getTelegramId().equals(userId)) {
            return RefundResult.failed(
                    "Refund received, but the original payment could not be matched. Owner has been notified.",
                    "Stars refund user mismatch\nUpdate user: %d\nPayment user: %d\nCharge: %s"
                            .formatted(userId, payment.getTelegramId(), chargeId)
            );
        }
        long paymentUserId = payment.getTelegramId();
        if ("refunded".equals(payment.getStatus())) {
            log.info("Duplicate Stars refund charge {} for user {}",
                    chargeId, LogUtils.hashUserId(paymentUserId));
            return RefundResult.duplicate("Refund already processed.");
        }
        if (!"completed".equals(payment.getStatus())) {
            return RefundResult.failed(
                    "Refund received, but the payment state could not be updated. Owner has been notified.",
                    "Stars refund has unexpected payment status\nUser: %d\nCharge: %s\nStatus: %s"
                            .formatted(userId, chargeId, payment.getStatus())
            );
        }

        String currentPlan = userSettingsService.getUsageStatus(paymentUserId, false).plan();
        boolean hasOtherCompletedPayment = paymentRepository
                .existsByTelegramIdAndStatusAndTelegramChargeIdNot(paymentUserId, "completed", chargeId);
        boolean downgrade = "pro".equals(currentPlan) && !hasOtherCompletedPayment;
        if (downgrade && !userSettingsService.downgradeToFree(paymentUserId)) {
            return RefundResult.failed(
                    "Refund received, but plan update failed. Owner has been notified.",
                    "Stars refund plan downgrade failed\nUser: %d\nCharge: %s".formatted(paymentUserId, chargeId)
            );
        }
        payment.setStatus("refunded");
        paymentRepository.saveAndFlush(payment);
        log.info("Processed Stars refund charge {} for user {}",
                chargeId, LogUtils.hashUserId(paymentUserId));
        String resultingPlan = downgrade ? "Free" : currentPlan;
        return RefundResult.completed(
                "Your Stars payment was refunded. Your current plan is %s.".formatted(resultingPlan),
                "Stars payment refunded\nUser: %d\nAmount: %d Stars\nCharge: %s\nCurrent plan: %s"
                        .formatted(paymentUserId, payment.getStarsAmount(), chargeId, resultingPlan)
        );
    }

    private Optional<String> validatePaymentFields(Long userId, String currency, Integer starsAmount, String payload) {
        if (userId == null) {
            return Optional.of("missing user");
        }
        if (!CURRENCY.equals(currency)) {
            return Optional.of("unexpected currency");
        }
        if (starsAmount == null || starsAmount != starsPrice) {
            return Optional.of("unexpected amount");
        }
        if (!payloadFor(userId).equals(payload)) {
            return Optional.of("unexpected payload");
        }
        return Optional.empty();
    }

    private String payloadFor(long userId) {
        return PAYLOAD_PREFIX + userId;
    }

    public record PaymentResult(String userMessage, String ownerMessage, boolean notifyOwners, boolean duplicate) {
        static PaymentResult completed(String userMessage, String ownerMessage) {
            return new PaymentResult(userMessage, ownerMessage, true, false);
        }

        static PaymentResult duplicate(String userMessage) {
            return new PaymentResult(userMessage, null, false, true);
        }

        static PaymentResult failed(String userMessage, String ownerMessage) {
            return new PaymentResult(userMessage, ownerMessage, true, false);
        }
    }

    public record RefundResult(String userMessage, String ownerMessage, boolean notifyOwners, boolean duplicate) {
        static RefundResult completed(String userMessage, String ownerMessage) {
            return new RefundResult(userMessage, ownerMessage, true, false);
        }

        static RefundResult duplicate(String userMessage) {
            return new RefundResult(userMessage, null, false, true);
        }

        static RefundResult failed(String userMessage, String ownerMessage) {
            return new RefundResult(userMessage, ownerMessage, true, false);
        }
    }
}
