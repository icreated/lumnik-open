package io.lumnik.hub.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed 60-second-window counter per key. Pure logic (clock passed in) so the unit
 * suite drives every branch; RateLimitFilter is the thin shell. State is one entry
 * per (bucket, tenant), swept on each rollover so it stays bounded by ACTIVE keys —
 * tenant churn over a long-running JVM never grows the map monotonically.
 */
public final class FixedWindowRateLimiter {

    /** allowed=false carries the seconds until the current window ends (≥1). */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private static final long WINDOW_SECONDS = 60;

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger();
        Window(long start) { this.start = start; }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Last window for which a sweep ran — stale entries are purged once per rollover. */
    private volatile long lastSweepStart = -1;

    public Decision tryAcquire(String key, int rpm, long nowEpochSeconds) {
        if (rpm <= 0) return new Decision(true, 0);
        long windowStart = (nowEpochSeconds / WINDOW_SECONDS) * WINDOW_SECONDS;
        // Bound the state by ACTIVE keys, not ever-seen keys: on each FORWARD rollover, drop
        // entries from strictly older windows. Monotonic guard: a backwards clock jump must
        // not trigger a sweep that purges the current window and resets live counters.
        // Racing sweeps are idempotent; a concurrent same-window entry is never removed.
        if (windowStart > lastSweepStart) {
            lastSweepStart = windowStart;
            windows.values().removeIf(w -> w.start < windowStart);
        }
        Window w = windows.compute(key,
            (k, old) -> old == null || old.start != windowStart ? new Window(windowStart) : old);
        // Saturate at rpm+1: an abusive burst must not wrap the counter negative and
        // slip back under the limit within the same window.
        int c = w.count.updateAndGet(v -> v > rpm ? v : v + 1);
        if (c <= rpm) return new Decision(true, 0);
        return new Decision(false, Math.max(1, windowStart + WINDOW_SECONDS - nowEpochSeconds));
    }

    /** Test hook: how many (bucket, tenant) entries are currently tracked. */
    int trackedKeys() {
        return windows.size();
    }
}
