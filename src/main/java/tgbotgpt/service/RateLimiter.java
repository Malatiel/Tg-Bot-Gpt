package tgbotgpt.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-user rate limiter based on a sliding window.
 */
@Component
public class RateLimiter {

    @Value("${bot.rate.limit:10}")
    private int maxRequests;

    @Value("${bot.rate.window.seconds:60}")
    private int windowSeconds;

    private final ConcurrentHashMap<Long, Deque<Long>> requestTimestamps = new ConcurrentHashMap<>();

    public boolean isAllowed(Long userId) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;

        Deque<Long> timestamps = requestTimestamps.computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());

        // Remove expired timestamps
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxRequests) {
            return false;
        }

        timestamps.addLast(now);
        return true;
    }

    public int getRemainingRequests(Long userId) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;

        Deque<Long> timestamps = requestTimestamps.get(userId);
        if (timestamps == null) {
            return maxRequests;
        }

        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }

        return Math.max(0, maxRequests - timestamps.size());
    }

    public long getSecondsUntilReset(Long userId) {
        Deque<Long> timestamps = requestTimestamps.get(userId);
        if (timestamps == null || timestamps.isEmpty()) {
            return 0;
        }
        long oldest = timestamps.peekFirst();
        long resetAt = oldest + windowSeconds * 1000L;
        long remaining = (resetAt - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }
}
