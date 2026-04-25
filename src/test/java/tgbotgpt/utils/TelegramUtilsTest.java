package tgbotgpt.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramUtilsTest {

    @Test
    void shouldReturnSinglePartForShortMessage() {
        List<String> parts = TelegramUtils.splitMessage("Hello, world!");
        assertEquals(1, parts.size());
        assertEquals("Hello, world!", parts.get(0));
    }

    @Test
    void shouldReturnEmptyForNull() {
        assertTrue(TelegramUtils.splitMessage(null).isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyString() {
        assertTrue(TelegramUtils.splitMessage("").isEmpty());
    }

    @Test
    void shouldNotSplitWhenUnderLimit() {
        String text = "a".repeat(3000) + "\n" + "b".repeat(100);
        List<String> parts = TelegramUtils.splitMessage(text);
        assertEquals(1, parts.size()); // 3101 chars, fits in one message
    }

    @Test
    void shouldSplitLongMessageOnNewline() {
        String line1 = "a".repeat(3000);
        String line2 = "b".repeat(3000);
        String text = line1 + "\n" + line2;
        List<String> parts = TelegramUtils.splitMessage(text);
        assertEquals(2, parts.size());
        assertEquals(line1, parts.get(0));
        assertEquals(line2, parts.get(1));
    }

    @Test
    void shouldSplitOnSpaceWhenNoNewline() {
        String text = ("word ".repeat(1000)).trim(); // ~5000 chars, no newlines
        List<String> parts = TelegramUtils.splitMessage(text);
        assertTrue(parts.size() >= 2);
        for (String part : parts) {
            assertTrue(part.length() <= TelegramUtils.MAX_MESSAGE_LENGTH);
        }
    }

    @Test
    void shouldHardCutWhenNoBreakpoint() {
        String text = "x".repeat(8192);
        List<String> parts = TelegramUtils.splitMessage(text);
        assertEquals(2, parts.size());
        assertEquals(4096, parts.get(0).length());
        assertEquals(4096, parts.get(1).length());
    }

    @Test
    void shouldHandleExactlyMaxLength() {
        String text = "a".repeat(4096);
        List<String> parts = TelegramUtils.splitMessage(text);
        assertEquals(1, parts.size());
    }

    @Test
    void shouldSplitIntoMultipleParts() {
        String text = "a".repeat(12288); // 3 * 4096
        List<String> parts = TelegramUtils.splitMessage(text);
        assertEquals(3, parts.size());
    }

    @Test
    void shouldPreserveContentAfterSplit() {
        String original = "Hello world\nThis is a test\nWith multiple lines";
        List<String> parts = TelegramUtils.splitMessage(original);
        String joined = String.join("", parts);
        // Content should be fully preserved (minus trimmed leading whitespace)
        assertEquals(original, joined);
    }

    @Test
    void shouldFitMessageToTelegramLimit() {
        String text = "a".repeat(5000);

        String result = TelegramUtils.fitMessage(text);

        assertEquals(TelegramUtils.MAX_MESSAGE_LENGTH, result.length());
    }

    @Test
    void shouldKeepShortFitMessageUnchanged() {
        String text = "Hello";

        assertEquals(text, TelegramUtils.fitMessage(text));
    }
}
