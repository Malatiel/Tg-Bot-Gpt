package tgbotgpt.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class BotMetricsService {

    private final MeterRegistry meterRegistry;

    public BotMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordOpenAiRequest(String mode, String operation) {
        meterRegistry.counter("tgbotgpt.openai.requests",
                "mode", normalize(mode),
                "operation", normalize(operation)
        ).increment();
    }

    public void recordOpenAiError(String mode, String operation, Throwable error) {
        meterRegistry.counter("tgbotgpt.openai.errors",
                "mode", normalize(mode),
                "operation", normalize(operation),
                "error", errorType(error)
        ).increment();
    }

    public void recordTelegramSend(boolean markdown, boolean success) {
        meterRegistry.counter("tgbotgpt.telegram.send",
                "format", markdown ? "markdown" : "plain",
                "result", success ? "success" : "failure"
        ).increment();
    }

    public void recordTelegramEdit(boolean success) {
        meterRegistry.counter("tgbotgpt.telegram.edit",
                "result", success ? "success" : "failure"
        ).increment();
    }

    public void recordExecutorRejection() {
        meterRegistry.counter("tgbotgpt.executor.rejected").increment();
    }

    public void recordTelegramRetry(String operation) {
        meterRegistry.counter("tgbotgpt.telegram.retry",
                "operation", normalize(operation)
        ).increment();
    }

    public void recordOperationDuration(String operation, boolean success, Duration duration) {
        Timer.builder("tgbotgpt.operation.duration")
                .tag("operation", normalize(operation))
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .record(duration);
    }

    private String errorType(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        return error.getClass().getSimpleName();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase();
    }
}
