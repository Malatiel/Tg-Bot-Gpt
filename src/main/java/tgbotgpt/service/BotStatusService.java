package tgbotgpt.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class BotStatusService {

    private final DataSource dataSource;
    private final Optional<BuildProperties> buildProperties;
    private final Optional<HealthEndpoint> healthEndpoint;
    private final Instant startedAt = Instant.now();

    @Value("${spring.application.name:Tg-Bot-Gpt}")
    private String applicationName;

    @Value("${project.version:0.1.2}")
    private String fallbackVersion;

    @Value("${openai.api.mode:responses}")
    private String apiMode;

    @Value("${openai.model}")
    private String defaultModel;

    @Value("${bot.stream.enabled:true}")
    private boolean streamingEnabled;

    public BotStatusService(DataSource dataSource, Optional<BuildProperties> buildProperties,
                            Optional<HealthEndpoint> healthEndpoint) {
        this.dataSource = dataSource;
        this.buildProperties = buildProperties;
        this.healthEndpoint = healthEndpoint;
    }

    public BotStatusSnapshot getStatus() {
        return new BotStatusSnapshot(
                applicationName,
                version(),
                applicationStatus(),
                apiMode,
                defaultModel,
                streamingEnabled,
                Duration.between(startedAt, Instant.now()),
                databaseStatus()
        );
    }

    private String version() {
        return buildProperties
                .map(BuildProperties::getVersion)
                .filter(version -> version != null && !version.isBlank())
                .orElse(fallbackVersion);
    }

    private String applicationStatus() {
        return healthEndpoint
                .map(endpoint -> endpoint.health().getStatus().getCode())
                .filter(status -> status != null && !status.isBlank())
                .orElse("UP");
    }

    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    public record BotStatusSnapshot(
            String applicationName,
            String version,
            String applicationStatus,
            String apiMode,
            String defaultModel,
            boolean streamingEnabled,
            Duration uptime,
            String databaseStatus
    ) {
    }
}
