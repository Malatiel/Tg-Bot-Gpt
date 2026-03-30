package tgbotgpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tgbotgpt.model.entity.BotUser;

public interface BotUserRepository extends JpaRepository<BotUser, Long> {
}
