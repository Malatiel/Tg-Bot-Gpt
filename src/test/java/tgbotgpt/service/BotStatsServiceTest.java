package tgbotgpt.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import tgbotgpt.model.entity.BotUser;
import tgbotgpt.repository.BotUserPaymentRepository;
import tgbotgpt.repository.BotUserRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotStatsServiceTest {

    @Mock
    private BotUserRepository userRepository;
    @Mock
    private BotUserPaymentRepository paymentRepository;

    @Test
    void getAdminStatsAggregatesUsersPaymentsAndTopUsers() {
        BotUser alice = user(1L, "alice", "pro", 150, 45000);
        BotUser bob = user(2L, null, null, 12, 3000);
        when(userRepository.count()).thenReturn(42L);
        when(userRepository.countUsersByBillingPlan()).thenReturn(List.of(
                new Object[]{"free", 30L},
                new Object[]{"trial", 5L},
                new Object[]{"pro", 6L},
                new Object[]{"owner", 1L}
        ));
        when(userRepository.findAllByOrderByTotalMessagesDesc(any(Pageable.class))).thenReturn(List.of(alice, bob));
        when(paymentRepository.countByStatus("completed")).thenReturn(8L);
        when(paymentRepository.countDistinctCompletedPayers()).thenReturn(6L);
        when(paymentRepository.sumCompletedStars()).thenReturn(1200L);
        BotStatsService service = new BotStatsService(userRepository, paymentRepository);

        BotStatsService.AdminStats stats = service.getAdminStats();

        assertEquals(42L, stats.totalUsers());
        assertEquals(30L, stats.planCounts().get("free"));
        assertEquals(5L, stats.planCounts().get("trial"));
        assertEquals(8L, stats.completedPayments());
        assertEquals(6L, stats.distinctPayers());
        assertEquals(1200L, stats.completedStars());
        assertEquals("alice", stats.topUsers().get(0).username());
        assertEquals("unknown", stats.topUsers().get(1).billingPlan());
    }

    @Test
    void getAdminStatsSkipsNullPlanRowsAndKeepsUnknownPlans() {
        when(userRepository.count()).thenReturn(3L);
        when(userRepository.countUsersByBillingPlan()).thenReturn(List.of(
                new Object[]{null, 99L},
                new Object[]{"", 1L},
                new Object[]{"VIP", 2L},
                new Object[]{"FREE", 1}
        ));
        when(userRepository.findAllByOrderByTotalMessagesDesc(any(Pageable.class))).thenReturn(List.of());
        BotStatsService service = new BotStatsService(userRepository, paymentRepository);

        BotStatsService.AdminStats stats = service.getAdminStats();

        assertEquals(1L, stats.planCounts().get("free"));
        assertEquals(0L, stats.planCounts().get("trial"));
        assertEquals(2L, stats.planCounts().get("vip"));
        assertFalse(stats.planCounts().containsKey(""));
    }

    private BotUser user(Long id, String username, String plan, int messages, int tokens) {
        BotUser user = new BotUser(id, username, null);
        user.setBillingPlan(plan);
        user.setTotalMessages(messages);
        user.setTotalTokensUsed(tokens);
        return user;
    }
}
