package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.RestResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResponseValidatorTest {

    @Test
    void noop_validator_accepts_anything() {
        ResponseValidator v = new NoopResponseValidator();
        v.validate(RestResponse.of(200, Map.of(), "{}"));
        v.validate(RestResponse.of(500, Map.of(), "{\"error\":\"x\"}"));
        // no exception = pass
    }

    @Test
    void retry_executor_triggers_retry_on_transient_validator_exception() throws Exception {
        RetryPolicy policy = new RetryPolicy(3, "exponential", Duration.ofMillis(10), Duration.ofMillis(100),
            0.0, Set.of(500, 502, 503), true);
        AtomicInteger calls = new AtomicInteger();
        ResponseValidator v = resp -> {
            // First two responses look "OK" (HTTP 200) but the validator says "transient" on the first two
            if (calls.get() < 3) throw new TransientFailureException("simulated retryable error", null);
        };
        RestResponse r = new RetryExecutor(policy, v).execute(() -> {
            calls.incrementAndGet();
            return RestResponse.of(200, Map.of(), "{}");
        });
        assertEquals(200, r.status());
        assertEquals(3, calls.get());
    }
}
