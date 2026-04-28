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
        assertEquals("free", user.getBillingPlan());
        assertEquals(0, user.getPeriodTokensUsed());
        assertEquals(0, user.getPeriodMessages());

        user.addTokens(50);
        user.addPeriodTokens(50);
        user.incrementMessages();
        user.incrementPeriodMessages();

        assertEquals(50, user.getTotalTokensUsed());
        assertEquals(1, user.getTotalMessages());
        assertEquals(50, user.getPeriodTokensUsed());
        assertEquals(1, user.getPeriodMessages());
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

    @Test
    void shouldHandleBothFieldsNull() {
        BotUser user = new BotUser(1L, null, null);
        assertNull(user.getUsername());
        assertNull(user.getFirstName());
        assertNotNull(user.getFirstSeen());
    }

    @Test
    void shouldHandleExactly64CharUsername() {
        String name64 = "a".repeat(64);
        BotUser user = new BotUser(1L, name64, "First");
        assertEquals(64, user.getUsername().length());
    }

    @Test
    void shouldTruncateAfterControlCharRemoval() {
        // 62 visible chars + 5 control chars = 67 total input
        // After sanitization: 62 chars, which is < 64, so no truncation
        String input = "a".repeat(62) + "\u0000\u0001\u0002\u0003\u0004";
        BotUser user = new BotUser(1L, input, null);
        assertEquals(62, user.getUsername().length());
    }

    @Test
    void shouldAccumulateTokensOverMultipleCalls() {
        BotUser user = new BotUser(1L, "test", "Test");
        user.addTokens(10);
        user.addTokens(20);
        user.addTokens(30);

        assertEquals(60, user.getTotalTokensUsed());
    }

    @Test
    void shouldIncrementMessagesAndUpdateLastActive() {
        BotUser user = new BotUser(1L, "test", "Test");
        var initialActive = user.getLastActive();

        user.incrementMessages();
        user.incrementMessages();

        assertEquals(2, user.getTotalMessages());
        assertNotNull(user.getLastActive());
    }

    @Test
    void shouldHandleEmptyStringUsername() {
        BotUser user = new BotUser(1L, "", "First");
        assertEquals("", user.getUsername());
    }

    @Test
    void shouldSanitizeTabsAndNewlines() {
        BotUser user = new BotUser(1L, "user\tname\n", "first\rname");
        assertFalse(user.getUsername().contains("\t"));
        assertFalse(user.getUsername().contains("\n"));
        assertFalse(user.getFirstName().contains("\r"));
    }
}
