package io.lumnik.hub.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed 60-second-window counter per key. Pure logic (clock passed in) so the unit
 * suite drives every branch; RateLimitFilter is the thin shell. State is one entry
 * per key — a tenant's ceiling (bucket|tenant), a user's share (bucket|tenant|user) and a user-less caller's budget
 * (bucket|tenant|-), #1996 — swept on each rollover so it stays bounded by ACTIVE keys —
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
        // The count is the slots GRANTED, never past rpm: a refusal leaves it as it is, so an abusive burst cannot
        // wrap it and release() gives back exactly a slot that was taken (#1996).
        for (int c = w.count.get(); c < rpm; c = w.count.get()) {
            if (w.count.compareAndSet(c, c + 1)) return new Decision(true, 0);
        }
        return new Decision(false, Math.max(1, windowStart + WINDOW_SECONDS - nowEpochSeconds));
    }

    /**
     * Gives back a slot this key took in the current window: a call a later limit refused (#1996, the tenant's
     * ceiling over a user's share) must not have spent it. A key from an older window has nothing to give back.
     */
    public void release(String key, long nowEpochSeconds) {
        long windowStart = (nowEpochSeconds / WINDOW_SECONDS) * WINDOW_SECONDS;
        windows.computeIfPresent(key, (k, w) -> {
            if (w.start == windowStart) w.count.updateAndGet(v -> v > 0 ? v - 1 : 0);
            return w;
        });
    }

    /** Test hook: how many keys are currently tracked. */
    int trackedKeys() {
        return windows.size();
    }
}
