package tgbotgpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tgbotgpt.model.entity.BotUser;

import java.time.LocalDateTime;
import java.util.List;

public interface BotUserRepository extends JpaRepository<BotUser, Long> {
    List<BotUser> findByBillingPlanAndPlanExpiresAtBefore(String billingPlan, LocalDateTime cutoff);
}
