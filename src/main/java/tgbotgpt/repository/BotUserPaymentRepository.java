package tgbotgpt.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tgbotgpt.model.entity.BotUserPayment;

import java.util.Optional;

public interface BotUserPaymentRepository extends JpaRepository<BotUserPayment, Long> {
    boolean existsByTelegramChargeId(String telegramChargeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BotUserPayment> findByTelegramChargeId(String telegramChargeId);

    boolean existsByTelegramIdAndStatusAndTelegramChargeIdNot(
            Long telegramId, String status, String telegramChargeId);

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(p.starsAmount), 0) FROM BotUserPayment p WHERE p.status = 'completed'")
    long sumCompletedStars();

    @Query("SELECT COUNT(DISTINCT p.telegramId) FROM BotUserPayment p WHERE p.status = 'completed'")
    long countDistinctCompletedPayers();
}
