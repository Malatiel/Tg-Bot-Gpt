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

        Deque<Long> timestamps = requestTimestamps.computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            removeExpired(timestamps, now);
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    public int getRemainingRequests(Long userId) {
        Deque<Long> timestamps = requestTimestamps.get(userId);
        if (timestamps == null) {
            return maxRequests;
        }

        synchronized (timestamps) {
            removeExpired(timestamps, System.currentTimeMillis());
            return Math.max(0, maxRequests - timestamps.size());
        }
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public long getSecondsUntilReset(Long userId) {
        Deque<Long> timestamps = requestTimestamps.get(userId);
        if (timestamps == null || timestamps.isEmpty()) {
            return 0;
        }
        synchronized (timestamps) {
            long now = System.currentTimeMillis();
            removeExpired(timestamps, now);
            if (timestamps.isEmpty()) {
                return 0;
            }
            long oldest = timestamps.peekFirst();
            long resetAt = oldest + windowSeconds * 1000L;
            long remaining = (resetAt - now) / 1000;
            return Math.max(0, remaining);
        }
    }

    private void removeExpired(Deque<Long> timestamps, long now) {
        long windowStart = now - windowSeconds * 1000L;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }
    }
}
