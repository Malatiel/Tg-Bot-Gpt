package tgbotgpt.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BotAdminService {

    private final BotStatusService statusService;

    @Value("${bot.owner.ids:}")
    private String ownerIds;

    private Set<Long> owners = Set.of();

    public BotAdminService(BotStatusService statusService) {
        this.statusService = statusService;
    }

    @PostConstruct
    private void init() {
        owners = parseOwners(ownerIds);
    }

    public boolean isOwner(Long userId) {
        return userId != null && owners.contains(userId);
    }

    public String statusFor(Long userId) {
        if (!isOwner(userId)) {
            return "Sorry, this command is only available to the bot owner.";
        }
        return formatStatus(statusService.getStatus());
    }

    Set<Long> parseOwners(String rawOwnerIds) {
        if (rawOwnerIds == null || rawOwnerIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rawOwnerIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::parseOwnerId)
                .filter(id -> id != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long parseOwnerId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid bot owner id: {}", value);
            return null;
        }
    }

    private String formatStatus(BotStatusService.BotStatusSnapshot status) {
        return String.format("""
                Service: %s
                Version: %s
                Status: %s
                Uptime: %s
                OpenAI API mode: %s
                Default model: %s
                Streaming: %s
                Database: %s
                """,
                status.applicationName(),
                status.version(),
                status.applicationStatus(),
                formatDuration(status.uptime()),
                status.apiMode(),
                status.defaultModel(),
                status.streamingEnabled() ? "enabled" : "disabled",
                status.databaseStatus()
        ).strip();
    }

    private String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return "%dh %dm %ds".formatted(hours, minutes, remainingSeconds);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, remainingSeconds);
        }
        return "%ds".formatted(remainingSeconds);
    }
}
