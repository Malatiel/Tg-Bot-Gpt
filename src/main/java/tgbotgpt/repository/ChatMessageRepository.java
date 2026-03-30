package tgbotgpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import tgbotgpt.model.entity.ChatMessage;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    void deleteByUserId(Long userId);

    /**
     * Auto-cleanup messages older than a given date to prevent unbounded growth.
     */
    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.createdAt < :before")
    int deleteOlderThan(LocalDateTime before);
}
