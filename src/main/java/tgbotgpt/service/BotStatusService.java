package tgbotgpt.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class BotStatusService {

    private final Optional<BuildProperties> buildProperties;
    private final Optional<HealthEndpoint> healthEndpoint;
    private final Instant startedAt = Instant.now();

    @Value("${spring.application.name:Tg-Bot-Gpt}")
    private String applicationName;

    @Value("${project.version:unknown}")
    private String fallbackVersion;

    @Value("${openai.api.mode:responses}")
    private String apiMode;

    @Value("${openai.model}")
    private String defaultModel;

    @Value("${bot.stream.enabled:true}")
    private boolean streamingEnabled;

    public BotStatusService(Optional<BuildProperties> buildProperties, Optional<HealthEndpoint> healthEndpoint) {
        this.buildProperties = buildProperties;
        this.healthEndpoint = healthEndpoint;
    }

    public BotStatusSnapshot getStatus() {
        return new BotStatusSnapshot(
                applicationName,
                version(),
                componentStatus(null),
                apiMode,
                defaultModel,
                streamingEnabled,
                Duration.between(startedAt, Instant.now()),
                componentStatus("db"),
                componentStatus("openai")
        );
    }

    private String version() {
        return buildProperties
                .map(BuildProperties::getVersion)
                .filter(version -> version != null && !version.isBlank())
                .orElse(fallbackVersion);
    }

    /**
     * Reads a status code from the actuator HealthEndpoint. {@code component == null}
     * means the aggregate (overall) status. Falls back to {@code UNKNOWN} if the
     * endpoint or component is unavailable (e.g. tests without actuator wiring).
     */
    private String componentStatus(String component) {
        if (healthEndpoint.isEmpty()) {
            return "UNKNOWN";
        }
        HealthEndpoint endpoint = healthEndpoint.get();
        HealthComponent health = component == null ? endpoint.health() : endpoint.healthForPath(component);
        if (health == null || health.getStatus() == null) {
            return "UNKNOWN";
        }
        String code = health.getStatus().getCode();
        return (code == null || code.isBlank()) ? "UNKNOWN" : code;
    }

    public record BotStatusSnapshot(
            String applicationName,
            String version,
            String applicationStatus,
            String apiMode,
            String defaultModel,
            boolean streamingEnabled,
            Duration uptime,
            String databaseStatus,
            String openAiStatus
    ) {
    }
}
