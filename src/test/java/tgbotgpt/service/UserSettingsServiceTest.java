package tgbotgpt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class UserSettingsServiceTest {

    private UserSettingsService service;

    @BeforeEach
    void setUp() {
        service = new UserSettingsService();
        ReflectionTestUtils.setField(service, "defaultModel", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "allowedModelsString", "gpt-4o-mini,gpt-4o,gpt-4-turbo");
        // Trigger @PostConstruct manually
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    @Test
    void shouldReturnDefaultModelForNewUser() {
        assertEquals("gpt-4o-mini", service.getModel(1L));
    }

    @Test
    void shouldSetAllowedModel() {
        assertTrue(service.setModel(1L, "gpt-4o"));
        assertEquals("gpt-4o", service.getModel(1L));
    }

    @Test
    void shouldRejectUnknownModel() {
        assertFalse(service.setModel(1L, "gpt-5-nonexistent"));
        assertEquals("gpt-4o-mini", service.getModel(1L));
    }

    @Test
    void shouldTrackModelsPerUser() {
        service.setModel(1L, "gpt-4o");
        service.setModel(2L, "gpt-4-turbo");

        assertEquals("gpt-4o", service.getModel(1L));
        assertEquals("gpt-4-turbo", service.getModel(2L));
        assertEquals("gpt-4o-mini", service.getModel(3L));
    }

    @Test
    void shouldOverwritePreviousModel() {
        service.setModel(1L, "gpt-4o");
        service.setModel(1L, "gpt-4-turbo");

        assertEquals("gpt-4-turbo", service.getModel(1L));
    }

    @Test
    void shouldReturnAllAllowedModels() {
        assertEquals(3, service.getAllowedModels().size());
        assertTrue(service.getAllowedModels().contains("gpt-4o-mini"));
        assertTrue(service.getAllowedModels().contains("gpt-4o"));
        assertTrue(service.getAllowedModels().contains("gpt-4-turbo"));
    }

    @Test
    void shouldReturnDefaultModel() {
        assertEquals("gpt-4o-mini", service.getDefaultModel());
    }
}
