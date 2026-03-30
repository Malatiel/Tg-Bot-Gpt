package tgbotgpt.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_users")
@Getter
@Setter
@NoArgsConstructor
public class BotUser {

    @Id
    private Long telegramId;

    @Column(length = 64)
    private String username;

    @Column(length = 64)
    private String firstName;

    @Column(length = 32)
    private String selectedModel;

    @Column(length = 500)
    private String customPrompt;

    @Column(nullable = false)
    private int totalTokensUsed;

    @Column(nullable = false)
    private int totalMessages;

    @Column(nullable = false)
    private LocalDateTime firstSeen;

    @Column(nullable = false)
    private LocalDateTime lastActive;

    public BotUser(Long telegramId, String username, String firstName) {
        this.telegramId = telegramId;
        this.username = sanitize(username);
        this.firstName = sanitize(firstName);
        this.totalTokensUsed = 0;
        this.totalMessages = 0;
        this.firstSeen = LocalDateTime.now();
        this.lastActive = LocalDateTime.now();
    }

    public void addTokens(int tokens) {
        this.totalTokensUsed += tokens;
    }

    public void incrementMessages() {
        this.totalMessages++;
        this.lastActive = LocalDateTime.now();
    }

    /**
     * Strip control characters and limit length to prevent injection/abuse.
     */
    private static String sanitize(String input) {
        if (input == null) return null;
        String cleaned = input.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.substring(0, Math.min(cleaned.length(), 64));
    }
}
