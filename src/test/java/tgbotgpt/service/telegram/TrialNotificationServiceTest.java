package tgbotgpt.service.telegram;

import org.junit.jupiter.api.Test;
import tgbotgpt.model.entity.BotUser;
import tgbotgpt.repository.BotUserRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrialNotificationServiceTest {

    @Test
    void marksTrialAsNotifiedAfterSuccessfulDelivery() {
        BotUserRepository repository = mock(BotUserRepository.class);
        TelegramBotService botService = mock(TelegramBotService.class);
        BotUser user = trialUser(1L);
        when(repository.findByBillingPlanAndTrialExpiryNotifiedFalseAndTrialEndsAtBetween(
                eq("trial"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(user));
        when(botService.sendProactiveMessage(eq(1L), anyString())).thenReturn(true);

        new TrialNotificationService(repository, botService).notifyExpiringTrials();

        assertTrue(user.isTrialExpiryNotified());
        verify(repository).save(user);
        verify(botService).sendProactiveMessage(eq(1L), anyString());
    }

    @Test
    void leavesTrialUnnotifiedWhenDeliveryFails() {
        BotUserRepository repository = mock(BotUserRepository.class);
        TelegramBotService botService = mock(TelegramBotService.class);
        BotUser user = trialUser(2L);
        when(repository.findByBillingPlanAndTrialExpiryNotifiedFalseAndTrialEndsAtBetween(
                eq("trial"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(user));
        when(botService.sendProactiveMessage(eq(2L), anyString())).thenReturn(false);

        new TrialNotificationService(repository, botService).notifyExpiringTrials();

        assertFalse(user.isTrialExpiryNotified());
        verify(repository, never()).save(any(BotUser.class));
    }

    @Test
    void continuesAfterOneDeliveryThrows() {
        BotUserRepository repository = mock(BotUserRepository.class);
        TelegramBotService botService = mock(TelegramBotService.class);
        BotUser first = trialUser(1L);
        BotUser second = trialUser(2L);
        when(repository.findByBillingPlanAndTrialExpiryNotifiedFalseAndTrialEndsAtBetween(
                eq("trial"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));
        when(botService.sendProactiveMessage(eq(1L), anyString()))
                .thenThrow(new IllegalStateException("Telegram unavailable"));
        when(botService.sendProactiveMessage(eq(2L), anyString())).thenReturn(true);

        new TrialNotificationService(repository, botService).notifyExpiringTrials();

        assertFalse(first.isTrialExpiryNotified());
        assertTrue(second.isTrialExpiryNotified());
        verify(repository).save(second);
        verify(repository, never()).save(first);
    }

    private BotUser trialUser(long id) {
        BotUser user = new BotUser(id, "trial", "Trial");
        user.setBillingPlan("trial");
        user.setTrialEndsAt(LocalDateTime.now().plusHours(24));
        return user;
    }
}
