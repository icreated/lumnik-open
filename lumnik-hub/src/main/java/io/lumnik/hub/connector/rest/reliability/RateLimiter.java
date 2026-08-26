package io.lumnik.hub.connector.rest.reliability;

import java.time.Duration;

public class RateLimiter {

    private final int capacity;
    private final long refillNanos;
    private double tokens;
    private long lastRefillNs;

    public RateLimiter(int count, Duration window) {
        this.capacity = count;
        this.refillNanos = window.toNanos() / count;
        this.tokens = count;
        this.lastRefillNs = System.nanoTime();
    }

    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return;
            }
            long waitNs = (long) ((1.0 - tokens) * refillNanos);
            wait(waitNs / 1_000_000, (int) (waitNs % 1_000_000));
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long delta = now - lastRefillNs;
        if (delta <= 0) return;
        tokens = Math.min(capacity, tokens + (double) delta / refillNanos);
        lastRefillNs = now;
    }
}
