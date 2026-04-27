package tgbotgpt.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotAdminServiceTest {

    @Test
    void shouldParseOwnerIdsAndIgnoreInvalidValues() {
        BotAdminService service = new BotAdminService(mock(BotStatusService.class));

        assertEquals(Set.of(1L, 2L), service.parseOwners("1, 2, invalid, "));
    }

    @Test
    void shouldRejectStatusForNonOwner() {
        BotAdminService service = new BotAdminService(mock(BotStatusService.class));
        ReflectionTestUtils.setField(service, "owners", Set.of(1L));

        assertEquals("Sorry, this command is only available to the bot owner.", service.statusFor(2L));
    }

    @Test
    void shouldFormatStatusForOwnerWithoutSecrets() {
        BotStatusService statusService = mock(BotStatusService.class);
        when(statusService.getStatus()).thenReturn(new BotStatusService.BotStatusSnapshot(
                "Tg-Bot-Gpt",
                "0.2.0",
                "UP",
                "responses",
                "gpt-4o-mini",
                true,
                Duration.ofSeconds(65),
                "UP",
                "DEGRADED"
        ));
        BotAdminService service = new BotAdminService(statusService);
        ReflectionTestUtils.setField(service, "owners", Set.of(1L));

        String status = service.statusFor(1L);

        assertTrue(status.contains("Service: Tg-Bot-Gpt"));
        assertTrue(status.contains("Version: 0.2.0"));
        assertTrue(status.contains("Status: UP"));
        assertTrue(status.contains("OpenAI API mode: responses"));
        assertTrue(status.contains("Default model: gpt-4o-mini"));
        assertTrue(status.contains("Database: UP"));
        assertTrue(status.contains("OpenAI: DEGRADED"));
        assertFalse(status.toLowerCase().contains("token"));
        assertFalse(status.toLowerCase().contains("key"));
    }
}
