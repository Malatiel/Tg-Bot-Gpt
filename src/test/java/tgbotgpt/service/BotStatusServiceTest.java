package tgbotgpt.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotStatusServiceTest {

    @Test
    void shouldReturnStatusSnapshotFromHealthEndpoint() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(Health.up().build());
        when(endpoint.healthForPath("db")).thenReturn(Health.up().build());
        when(endpoint.healthForPath("openai")).thenReturn(Health.status("DEGRADED").build());

        BotStatusService service = new BotStatusService(Optional.empty(), Optional.of(endpoint));
        ReflectionTestUtils.setField(service, "applicationName", "Tg-Bot-Gpt");
        ReflectionTestUtils.setField(service, "fallbackVersion", "0.2.0");
        ReflectionTestUtils.setField(service, "apiMode", "responses");
        ReflectionTestUtils.setField(service, "defaultModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "streamingEnabled", true);

        BotStatusService.BotStatusSnapshot status = service.getStatus();

        assertEquals("Tg-Bot-Gpt", status.applicationName());
        assertEquals("0.2.0", status.version());
        assertEquals("UP", status.applicationStatus());
        assertEquals("responses", status.apiMode());
        assertEquals("gpt-4o-mini", status.defaultModel());
        assertTrue(status.streamingEnabled());
        assertEquals("UP", status.databaseStatus());
        assertEquals("DEGRADED", status.openAiStatus());
        assertFalse(status.uptime().isNegative());
    }

    @Test
    void shouldFallBackToUnknownWhenHealthEndpointAbsent() {
        BotStatusService service = new BotStatusService(Optional.empty(), Optional.empty());
        ReflectionTestUtils.setField(service, "applicationName", "Tg-Bot-Gpt");
        ReflectionTestUtils.setField(service, "fallbackVersion", "0.2.0");
        ReflectionTestUtils.setField(service, "apiMode", "responses");
        ReflectionTestUtils.setField(service, "defaultModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "streamingEnabled", true);

        BotStatusService.BotStatusSnapshot status = service.getStatus();

        assertEquals("UNKNOWN", status.applicationStatus());
        assertEquals("UNKNOWN", status.databaseStatus());
        assertEquals("UNKNOWN", status.openAiStatus());
    }
}
