package tgbotgpt.service.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tgbotgpt.model.entity.BotUser;
import tgbotgpt.repository.BotUserRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
class TrialNotificationService {
    private static final String PLAN_TRIAL = "trial";
    private static final String EXPIRY_MESSAGE = """
            Ваш пробный Pro заканчивается примерно через 24 часа.
            Чтобы продолжить пользоваться Pro, выберите план: /upgrade
            """.strip();

    private final BotUserRepository userRepository;
    private final TelegramBotService telegramBotService;

    TrialNotificationService(BotUserRepository userRepository, TelegramBotService telegramBotService) {
        this.userRepository = userRepository;
        this.telegramBotService = telegramBotService;
    }

    @Scheduled(cron = "${billing.trial.expiry.notification.cron:0 0 10 * * *}")
    public void notifyExpiringTrials() {
        LocalDateTime now = LocalDateTime.now();
        List<BotUser> users = userRepository
                .findByBillingPlanAndTrialExpiryNotifiedFalseAndTrialEndsAtBetween(
                        PLAN_TRIAL, now.plusHours(20), now.plusHours(44));

        for (BotUser user : users) {
            notifyUser(user);
        }
    }

    private void notifyUser(BotUser user) {
        try {
            if (!telegramBotService.sendProactiveMessage(user.getTelegramId(), EXPIRY_MESSAGE)) {
                log.warn("Trial expiry notification was not delivered for user {}", user.getTelegramId());
                return;
            }
            user.setTrialExpiryNotified(true);
            userRepository.save(user);
        } catch (RuntimeException e) {
            log.error("Failed to process trial expiry notification for user {}", user.getTelegramId(), e);
        }
    }
}
