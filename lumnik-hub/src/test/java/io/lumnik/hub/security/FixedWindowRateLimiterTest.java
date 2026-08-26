package io.lumnik.hub.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure window logic — the filter (RateLimitFilter) stays a thin shell over this. */
class FixedWindowRateLimiterTest {

    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

    @Test
    void allows_up_to_rpm_within_one_window_then_rejects() {
        long t = 1_000_000_020L; // exactly on a window boundary (divisible by 60)
        assertTrue(limiter.tryAcquire("chat|1", 2, t).allowed());
        assertTrue(limiter.tryAcquire("chat|1", 2, t).allowed());
        var d = limiter.tryAcquire("chat|1", 2, t);
        assertFalse(d.allowed());
        long windowStart = (t / 60) * 60;
        assertEquals(windowStart + 60 - t, d.retryAfterSeconds());
    }

    @Test
    void window_rollover_resets_the_count() {
        long t = 2_000_000_000L;
        assertTrue(limiter.tryAcquire("chat|1", 1, t).allowed());
        assertFalse(limiter.tryAcquire("chat|1", 1, t + 1).allowed());
        assertTrue(limiter.tryAcquire("chat|1", 1, t + 60).allowed()); // next window
    }

    @Test
    void keys_are_isolated() {
        long t = 3_000_000_000L;
        assertTrue(limiter.tryAcquire("chat|1", 1, t).allowed());
        assertTrue(limiter.tryAcquire("chat|2", 1, t).allowed()); // other tenant unaffected
        assertTrue(limiter.tryAcquire("search|1", 1, t).allowed()); // other endpoint unaffected
    }

    @Test
    void rpm_zero_or_negative_disables_the_limit() {
        long t = 4_000_000_000L;
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire("chat|1", 0, t).allowed());
            assertTrue(limiter.tryAcquire("chat|1", -1, t).allowed());
        }
    }

    @Test
    void sustained_over_limit_burst_stays_rejected() {
        // The counter saturates at rpm+1 — a burst must never wrap it back under the limit.
        long t = 6_000_000_000L;
        assertTrue(limiter.tryAcquire("chat|7", 1, t).allowed());
        for (int i = 0; i < 10_000; i++) {
            assertFalse(limiter.tryAcquire("chat|7", 1, t).allowed());
        }
    }

    @Test
    void stale_windows_are_evicted_on_rollover() {
        // Tenant churn must not grow the map for the JVM's lifetime: after a rollover,
        // only keys touched in the current window remain tracked.
        long t = 7_000_000_020L;
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("chat|" + i, 5, t);
        }
        assertEquals(100, limiter.trackedKeys());
        limiter.tryAcquire("chat|0", 5, t + 60);
        assertEquals(1, limiter.trackedKeys());
    }

    @Test
    void backwards_clock_jump_does_not_reset_live_counters() {
        // A caller with an earlier clock reading (NTP step, thread preempted with a stale
        // now) must never trigger a sweep that purges the CURRENT window's counters.
        long t = 8_000_000_040L;
        assertTrue(limiter.tryAcquire("chat|1", 1, t).allowed());
        assertFalse(limiter.tryAcquire("chat|1", 1, t).allowed());
        limiter.tryAcquire("other|1", 5, t - 120);
        assertFalse(limiter.tryAcquire("chat|1", 1, t).allowed());
    }

    @Test
    void retry_after_is_at_least_one_second() {
        long t = (5_000_000_000L / 60) * 60 + 59; // last second of a window
        assertTrue(limiter.tryAcquire("chat|9", 1, t).allowed());
        var d = limiter.tryAcquire("chat|9", 1, t);
        assertFalse(d.allowed());
        assertEquals(1, d.retryAfterSeconds());
    }
}
