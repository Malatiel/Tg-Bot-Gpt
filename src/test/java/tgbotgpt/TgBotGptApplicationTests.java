package tgbotgpt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TgBotGptApplicationTests {

    @Test
    void mainClassExists() {
        assertDoesNotThrow(() -> Class.forName("tgbotgpt.TgBotGptApplication"));
    }
}
