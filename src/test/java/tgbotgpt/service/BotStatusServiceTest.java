package tgbotgpt.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotStatusServiceTest {

    @Test
    void shouldReturnStatusSnapshotWithDatabaseUp() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isValid(2)).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        BotStatusService service = new BotStatusService(dataSource, Optional.empty());
        ReflectionTestUtils.setField(service, "applicationName", "Tg-Bot-Gpt");
        ReflectionTestUtils.setField(service, "fallbackVersion", "0.1.1");
        ReflectionTestUtils.setField(service, "apiMode", "responses");
        ReflectionTestUtils.setField(service, "defaultModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "streamingEnabled", true);

        BotStatusService.BotStatusSnapshot status = service.getStatus();

        assertEquals("Tg-Bot-Gpt", status.applicationName());
        assertEquals("0.1.1", status.version());
        assertEquals("responses", status.apiMode());
        assertEquals("gpt-4o-mini", status.defaultModel());
        assertTrue(status.streamingEnabled());
        assertEquals("UP", status.databaseStatus());
        assertFalse(status.uptime().isNegative());
    }

    @Test
    void shouldReturnDatabaseDownWhenConnectionFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new IllegalStateException("unavailable"));

        BotStatusService service = new BotStatusService(dataSource, Optional.empty());

        assertEquals("DOWN", service.getStatus().databaseStatus());
    }
}
