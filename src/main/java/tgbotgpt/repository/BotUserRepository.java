package tgbotgpt.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tgbotgpt.model.entity.BotUser;

import java.time.LocalDateTime;
import java.util.List;

public interface BotUserRepository extends JpaRepository<BotUser, Long> {
    List<BotUser> findByBillingPlanAndPlanExpiresAtBefore(String billingPlan, LocalDateTime cutoff);

    List<BotUser> findByBillingPlanAndTrialEndsAtBefore(String billingPlan, LocalDateTime cutoff);

    /** Most-recently-active users first; the limit is supplied via {@link Pageable}. */
    List<BotUser> findAllByOrderByLastActiveDesc(Pageable pageable);

    @Query("SELECT u.billingPlan, COUNT(u) FROM BotUser u GROUP BY u.billingPlan")
    List<Object[]> countUsersByBillingPlan();

    List<BotUser> findAllByOrderByTotalMessagesDesc(Pageable pageable);
}
