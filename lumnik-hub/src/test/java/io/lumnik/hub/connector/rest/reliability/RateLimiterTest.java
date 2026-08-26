package io.lumnik.hub.connector.rest.reliability;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void acquires_immediately_when_tokens_available() throws InterruptedException {
        RateLimiter rl = new RateLimiter(10, Duration.ofSeconds(1));
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) rl.acquire();
        assertTrue(System.currentTimeMillis() - t0 < 100, "should not block when bucket has tokens");
    }

    @Test
    void blocks_when_bucket_empty() throws InterruptedException {
        RateLimiter rl = new RateLimiter(2, Duration.ofMillis(200));
        rl.acquire();
        rl.acquire();
        long t0 = System.currentTimeMillis();
        rl.acquire();
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed >= 90, "expected refill wait, got " + elapsed + "ms");
    }
}
