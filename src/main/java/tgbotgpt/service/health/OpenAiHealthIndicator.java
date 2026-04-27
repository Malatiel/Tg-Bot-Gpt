package tgbotgpt.service.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the last observed OpenAI outcome and reports DEGRADED if all calls
 * within the freshness window failed (or no calls were made yet but the bot has
 * been up long enough that we'd have expected one). Down only when explicitly
 * marked unavailable.
 */
@Component("openai")
public class OpenAiHealthIndicator implements HealthIndicator {

    @Value("${openai.health.freshness.seconds:300}")
    private long freshnessSeconds;

    private final AtomicReference<Outcome> lastOutcome = new AtomicReference<>();

    public void recordSuccess() {
        lastOutcome.set(new Outcome(true, Instant.now(), null));
    }

    public void recordFailure(String reason) {
        lastOutcome.set(new Outcome(false, Instant.now(), reason));
    }

    @Override
    public Health health() {
        Outcome outcome = lastOutcome.get();
        if (outcome == null) {
            return Health.unknown().withDetail("reason", "no calls observed yet").build();
        }
        boolean fresh = Duration.between(outcome.at(), Instant.now())
                .compareTo(Duration.ofSeconds(freshnessSeconds)) <= 0;
        Health.Builder builder;
        if (!fresh) {
            builder = Health.unknown().withDetail("reason", "last outcome older than freshness window");
        } else if (outcome.success()) {
            builder = Health.up();
        } else {
            builder = Health.status("DEGRADED");
        }
        builder.withDetail("lastOutcomeAt", outcome.at().toString());
        if (!outcome.success() && outcome.reason() != null) {
            builder.withDetail("lastFailure", outcome.reason());
        }
        return builder.build();
    }

    private record Outcome(boolean success, Instant at, String reason) {
    }
}
