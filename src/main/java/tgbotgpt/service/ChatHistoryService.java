package tgbotgpt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tgbotgpt.model.entity.ChatMessage;
import tgbotgpt.repository.ChatMessageRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ChatHistoryService {

    private static final int MAX_HISTORY_DAYS = 30;

    private final ChatMessageRepository messageRepository;
    private final EncryptionService encryption;

    public ChatHistoryService(ChatMessageRepository messageRepository, EncryptionService encryption) {
        this.messageRepository = messageRepository;
        this.encryption = encryption;
    }

    @Transactional
    public void saveMessage(Long userId, String role, String content, Integer tokens) {
        ChatMessage msg = new ChatMessage(userId, role, encryption.encrypt(content), tokens);
        messageRepository.save(msg);
    }

    public List<ChatMessage> getRecentMessages(Long userId, int limit) {
        List<ChatMessage> messages = messageRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
        List<ChatMessage> reversed = messages.reversed();
        // Decrypt content in-place
        for (ChatMessage msg : reversed) {
            msg.setContent(encryption.decrypt(msg.getContent()));
        }
        return reversed;
    }

    @Transactional
    public void clearHistory(Long userId) {
        messageRepository.deleteByUserId(userId);
    }

    /**
     * Cleanup old messages daily at 3 AM to prevent unbounded DB growth.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(MAX_HISTORY_DAYS);
        int deleted = messageRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} messages older than {} days", deleted, MAX_HISTORY_DAYS);
        }
    }
}
