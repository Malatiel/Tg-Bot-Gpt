package tgbotgpt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void shouldReturnZeroRemainingWhenExactlyAtLimit() {
        Long userId = 1L;
        rateLimiter.isAllowed(userId);
        rateLimiter.isAllowed(userId);
        rateLimiter.isAllowed(userId);

        assertEquals(0, rateLimiter.getRemainingRequests(userId));
    }

    @Test
    void shouldNeverReturnNegativeRemaining() {
        Long userId = 1L;
        // Exhaust limit
        for (int i = 0; i < 5; i++) {
            rateLimiter.isAllowed(userId);
        }

        assertTrue(rateLimiter.getRemainingRequests(userId) >= 0);
    }

    @Test
    void shouldReturnMaxRequestsForNewUserRemaining() {
        assertEquals(3, rateLimiter.getRemainingRequests(999L));
    }

    @Test
    void shouldReturnZeroSecondsForUserWithExpiredWindow() {
        RateLimiter shortWindow = new RateLimiter();
        ReflectionTestUtils.setField(shortWindow, "maxRequests", 1);
        ReflectionTestUtils.setField(shortWindow, "windowSeconds", 1);

        Long userId = 1L;
        shortWindow.isAllowed(userId);

        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertEquals(0, shortWindow.getSecondsUntilReset(userId));
    }

    @Test
    void shouldNotAllowConcurrentRequestsPastLimit() throws InterruptedException {
        Long userId = 1L;
        int attempts = 25;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (rateLimiter.isAllowed(userId)) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

        assertEquals(3, allowed.get());
    }
}
