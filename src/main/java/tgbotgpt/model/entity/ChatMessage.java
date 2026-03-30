package tgbotgpt.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_messages_user_id", columnList = "userId"),
        @Index(name = "idx_chat_messages_created_at", columnList = "createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column
    private Integer tokensUsed;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ChatMessage(Long userId, String role, String content, Integer tokensUsed) {
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.tokensUsed = tokensUsed;
        this.createdAt = LocalDateTime.now();
    }
}
