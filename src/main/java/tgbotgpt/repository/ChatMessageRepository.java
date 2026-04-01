package tgbotgpt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import tgbotgpt.model.entity.ChatMessage;

import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    void deleteByUserId(Long userId);

    /**
     * Auto-cleanup messages older than a given date to prevent unbounded growth.
     */
    @Modifying
    @Query("DELETE FROM ChatMessage m WHERE m.createdAt < :before")
    int deleteOlderThan(LocalDateTime before);
}
