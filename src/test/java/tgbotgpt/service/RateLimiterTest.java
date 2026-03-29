package tgbotgpt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter();
        ReflectionTestUtils.setField(rateLimiter, "maxRequests", 3);
        ReflectionTestUtils.setField(rateLimiter, "windowSeconds", 60);
    }

    @Test
    void shouldAllowRequestsWithinLimit() {
        Long userId = 1L;
        assertTrue(rateLimiter.isAllowed(userId));
        assertTrue(rateLimiter.isAllowed(userId));
        assertTrue(rateLimiter.isAllowed(userId));
    }

    @Test
    void shouldBlockRequestsOverLimit() {
        Long userId = 1L;
        rateLimiter.isAllowed(userId);
        rateLimiter.isAllowed(userId);
        rateLimiter.isAllowed(userId);

        assertFalse(rateLimiter.isAllowed(userId));
    }

    @Test
    void shouldTrackDifferentUsersIndependently() {
        Long user1 = 1L;
        Long user2 = 2L;

        rateLimiter.isAllowed(user1);
        rateLimiter.isAllowed(user1);
        rateLimiter.isAllowed(user1);

        assertTrue(rateLimiter.isAllowed(user2));
        assertFalse(rateLimiter.isAllowed(user1));
    }

    @Test
    void shouldReturnCorrectRemainingRequests() {
        Long userId = 1L;

        assertEquals(3, rateLimiter.getRemainingRequests(userId));

        rateLimiter.isAllowed(userId);
        assertEquals(2, rateLimiter.getRemainingRequests(userId));

        rateLimiter.isAllowed(userId);
        assertEquals(1, rateLimiter.getRemainingRequests(userId));

        rateLimiter.isAllowed(userId);
        assertEquals(0, rateLimiter.getRemainingRequests(userId));
    }

    @Test
    void shouldResetAfterWindowExpires() {
        RateLimiter shortWindow = new RateLimiter();
        ReflectionTestUtils.setField(shortWindow, "maxRequests", 1);
        ReflectionTestUtils.setField(shortWindow, "windowSeconds", 1);

        Long userId = 1L;
        assertTrue(shortWindow.isAllowed(userId));
        assertFalse(shortWindow.isAllowed(userId));

        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertTrue(shortWindow.isAllowed(userId));
    }

    @Test
    void shouldReturnZeroSecondsForNewUser() {
        assertEquals(0, rateLimiter.getSecondsUntilReset(999L));
    }

    @Test
    void shouldReturnPositiveSecondsWhenRateLimited() {
        Long userId = 1L;
        rateLimiter.isAllowed(userId);
        rateLimiter.isAllowed(userId);
        rateLimiter.isAllowed(userId);

        long seconds = rateLimiter.getSecondsUntilReset(userId);
        assertTrue(seconds > 0);
        assertTrue(seconds <= 60);
    }
}
