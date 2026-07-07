package tgbotgpt.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tgbotgpt.model.entity.BotUser;
import tgbotgpt.repository.BotUserPaymentRepository;
import tgbotgpt.repository.BotUserRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class BotStatsService {

    private static final String STATUS_COMPLETED = "completed";
    private static final List<String> KNOWN_PLANS = List.of("free", "trial", "pro", "owner");

    private final BotUserRepository userRepository;
    private final BotUserPaymentRepository paymentRepository;

    public BotStatsService(BotUserRepository userRepository, BotUserPaymentRepository paymentRepository) {
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
    }

    public AdminStats getAdminStats() {
        Map<String, Long> planCounts = planCounts();
        List<TopUser> topUsers = userRepository.findAllByOrderByTotalMessagesDesc(PageRequest.of(0, 5)).stream()
                .map(this::toTopUser)
                .toList();

        return new AdminStats(
                userRepository.count(),
                planCounts,
                paymentRepository.countByStatus(STATUS_COMPLETED),
                paymentRepository.countDistinctCompletedPayers(),
                paymentRepository.sumCompletedStars(),
                topUsers
        );
    }

    private Map<String, Long> planCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        KNOWN_PLANS.forEach(plan -> counts.put(plan, 0L));

        for (Object[] row : userRepository.countUsersByBillingPlan()) {
            if (row == null || row.length < 2 || row[0] == null || !(row[1] instanceof Number count)) {
                continue;
            }
            String plan = row[0].toString().trim().toLowerCase(Locale.ROOT);
            if (plan.isBlank()) {
                continue;
            }
            counts.put(plan, count.longValue());
        }
        return counts;
    }

    private TopUser toTopUser(BotUser user) {
        return new TopUser(
                user.getTelegramId(),
                user.getUsername(),
                user.getTotalMessages(),
                user.getTotalTokensUsed(),
                normalizePlan(user.getBillingPlan())
        );
    }

    private String normalizePlan(String plan) {
        String normalized = plan == null ? "" : plan.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "unknown" : normalized;
    }

    public record AdminStats(
            long totalUsers,
            Map<String, Long> planCounts,
            long completedPayments,
            long distinctPayers,
            long completedStars,
            List<TopUser> topUsers
    ) {
    }

    public record TopUser(
            Long telegramId,
            String username,
            int totalMessages,
            int totalTokensUsed,
            String billingPlan
    ) {
    }
}
