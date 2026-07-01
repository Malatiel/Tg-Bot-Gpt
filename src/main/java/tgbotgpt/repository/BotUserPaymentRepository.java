package tgbotgpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tgbotgpt.model.entity.BotUserPayment;

public interface BotUserPaymentRepository extends JpaRepository<BotUserPayment, Long> {
    boolean existsByTelegramChargeId(String telegramChargeId);
}
