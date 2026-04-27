package tgbotgpt;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigurationDefaultsTest {

    @Test
    void shouldDefaultOpenAiApiModeToResponses() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(stream);
            properties.load(stream);
        }

        assertEquals("${OPENAI_API_MODE:responses}", properties.getProperty("openai.api.mode"));
    }

    @Test
    void shouldDefaultToCheapestGpt54ModelAndExposeSelectableModels() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(stream);
            properties.load(stream);
        }

        assertEquals("${OPENAI_MODEL:gpt-5.4-nano}", properties.getProperty("openai.model"));
        assertEquals("${OPENAI_ALLOWED_MODELS:gpt-5.4-nano,gpt-5.4-mini,gpt-4o-mini,gpt-4o}",
                properties.getProperty("openai.allowed.models"));
    }

    @Test
    void shouldBindActuatorToLocalhostManagementPortByDefault() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(stream);
            properties.load(stream);
        }

        assertEquals("8081", properties.getProperty("management.server.port"));
        assertEquals("127.0.0.1", properties.getProperty("management.server.address"));
    }

    @Test
    void shouldExposeOnlyHealthActuatorEndpointInProd() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application-prod.properties")) {
            assertNotNull(stream);
            properties.load(stream);
        }

        assertEquals("health", properties.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("never", properties.getProperty("management.endpoint.health.show-details"));
    }
}
