package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.rest.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    RetryPolicy P = new RetryPolicy(3, "exponential", Duration.ofMillis(10), Duration.ofMillis(100),
        0.0, Set.of(429, 500, 502, 503, 504, 408), true);

    @Test
    void retries_5xx_then_succeeds() throws Exception {
        AtomicInteger n = new AtomicInteger();
        RestResponse r = new RetryExecutor(P).execute(() -> {
            int call = n.incrementAndGet();
            return RestResponse.of(call < 3 ? 503 : 200, Map.of(), "{}");
        });
        assertEquals(200, r.status());
        assertEquals(3, n.get());
    }

    @Test
    void no_retry_on_4xx_other_than_429_408() throws Exception {
        AtomicInteger n = new AtomicInteger();
        RestResponse r = new RetryExecutor(P).execute(() -> {
            n.incrementAndGet();
            return RestResponse.of(400, Map.of(), "{}");
        });
        assertEquals(400, r.status());
        assertEquals(1, n.get());
    }

    @Test
    void respects_retry_after_on_429() throws Exception {
        AtomicInteger n = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        new RetryExecutor(P).execute(() -> {
            int call = n.incrementAndGet();
            return RestResponse.of(call < 2 ? 429 : 200, Map.of("Retry-After", List.of("0")), "{}");
        });
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 1000, "Retry-After: 0 means immediate retry; got " + elapsed + "ms");
    }
}
