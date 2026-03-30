package tgbotgpt.model.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BotUserTest {

    @Test
    void shouldSanitizeControlCharacters() {
        BotUser user = new BotUser(1L, "user\u0000name", "First\u0007Name");
        assertFalse(user.getUsername().contains("\u0000"));
        assertFalse(user.getFirstName().contains("\u0007"));
    }

    @Test
    void shouldHandleNullUsername() {
        BotUser user = new BotUser(1L, null, "First");
        assertNull(user.getUsername());
        assertEquals("First", user.getFirstName());
    }

    @Test
    void shouldTrackTokensAndMessages() {
        BotUser user = new BotUser(1L, "test", "Test");
        assertEquals(0, user.getTotalTokensUsed());
        assertEquals(0, user.getTotalMessages());

        user.addTokens(50);
        user.incrementMessages();

        assertEquals(50, user.getTotalTokensUsed());
        assertEquals(1, user.getTotalMessages());
    }

    @Test
    void shouldTruncateLongUsername() {
        String longName = "a".repeat(100);
        BotUser user = new BotUser(1L, longName, "First");
        assertTrue(user.getUsername().length() <= 64);
    }

    @Test
    void shouldSetTimestamps() {
        BotUser user = new BotUser(1L, "test", "Test");
        assertNotNull(user.getFirstSeen());
        assertNotNull(user.getLastActive());
    }
}
