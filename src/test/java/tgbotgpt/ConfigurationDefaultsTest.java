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
}
