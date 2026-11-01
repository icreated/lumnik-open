package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.rest.OutboundFailure;
import java.time.Duration;
import java.util.Set;

public record RetryPolicy(
    int maxAttempts,
    String backoff,
    Duration initialDelay,
    Duration maxDelay,
    double jitter,
    Set<Integer> retryOn,
    boolean retryOnNetwork
) {
    public static RetryPolicy defaults() {
        return new RetryPolicy(
            5, "exponential",
            Duration.ofSeconds(1), Duration.ofSeconds(60),
            0.1, Set.of(429, 500, 502, 503, 504, 408), true
        );
    }

    /** Whether a failure to reach the source is worth another attempt: never a certificate the trust refused (#1609). */
    public boolean retriesNetworkFailure(Throwable failure) {
        return retryOnNetwork && !OutboundFailure.isCertificateRefusal(failure);
    }
}
