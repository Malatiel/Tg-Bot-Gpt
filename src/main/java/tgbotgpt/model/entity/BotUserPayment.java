package tgbotgpt.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_user_payments")
@Getter
@Setter
@NoArgsConstructor
public class BotUserPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "telegram_charge_id", nullable = false, unique = true, length = 128)
    private String telegramChargeId;

    @Column(name = "stars_amount", nullable = false)
    private int starsAmount;

    @Column(nullable = false, length = 16)
    private String status = "completed";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public BotUserPayment(Long telegramId, String telegramChargeId, int starsAmount) {
        this.telegramId = telegramId;
        this.telegramChargeId = telegramChargeId;
        this.starsAmount = starsAmount;
    }
}
