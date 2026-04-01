package tgbotgpt.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageLogTest {

    @Test
    void shouldAddWithinCapacity() {
        MessageLog<String> log = new MessageLog<>(3);
        log.add("a");
        log.add("b");
        log.add("c");

        assertEquals(3, log.size());
        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void shouldEvictOldestWhenOverCapacity() {
        MessageLog<String> log = new MessageLog<>(3);
        log.add("a");
        log.add("b");
        log.add("c");
        log.add("d");

        assertEquals(3, log.size());
        assertEquals(List.of("b", "c", "d"), log);
    }

    @Test
    void shouldHandleMaxSizeOfOne() {
        MessageLog<String> log = new MessageLog<>(1);
        log.add("a");
        log.add("b");

        assertEquals(1, log.size());
        assertEquals("b", log.get(0));
    }

    @Test
    void shouldHandleAddAllWithEviction() {
        MessageLog<String> log = new MessageLog<>(3);
        log.addAll(0, List.of("a", "b", "c", "d", "e"));

        assertEquals(3, log.size());
        assertEquals(List.of("c", "d", "e"), log);
    }

    @Test
    void shouldHandleAddAfterAddAll() {
        MessageLog<String> log = new MessageLog<>(3);
        log.addAll(0, List.of("a", "b"));
        log.add("c");
        log.add("d");

        assertEquals(3, log.size());
        assertEquals(List.of("b", "c", "d"), log);
    }

    @Test
    void shouldHandleEmptyAddAll() {
        MessageLog<String> log = new MessageLog<>(3);
        log.addAll(0, List.of());

        assertEquals(0, log.size());
    }

    @Test
    void shouldWorkWithLargeCapacity() {
        MessageLog<Integer> log = new MessageLog<>(1000);
        for (int i = 0; i < 50; i++) {
            log.add(i);
        }

        assertEquals(50, log.size());
        assertEquals(0, log.get(0));
        assertEquals(49, log.get(49));
    }
}
