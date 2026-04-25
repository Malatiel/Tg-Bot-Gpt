package tgbotgpt.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotMetricsServiceTest {

    @Test
    void shouldRecordOpenAiAndTelegramMetricsWithoutUserData() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BotMetricsService service = new BotMetricsService(registry);

        service.recordOpenAiRequest("responses", "completion");
        service.recordOpenAiError("responses", "completion", new IllegalStateException("boom"));
        service.recordTelegramSend(true, false);
        service.recordTelegramEdit(true);

        assertEquals(1.0, registry.counter("tgbotgpt.openai.requests",
                "mode", "responses", "operation", "completion").count());
        assertEquals(1.0, registry.counter("tgbotgpt.openai.errors",
                "mode", "responses", "operation", "completion", "error", "IllegalStateException").count());
        assertEquals(1.0, registry.counter("tgbotgpt.telegram.send",
                "format", "markdown", "result", "failure").count());
        assertEquals(1.0, registry.counter("tgbotgpt.telegram.edit",
                "result", "success").count());
    }
}
