package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.RestResponse;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Random;

public class RetryExecutor {

    private final RetryPolicy policy;
    private final ResponseValidator validator;
    private final Random rng = new Random();

    /** Backwards-compatible constructor — delegates to the two-arg form with a no-op validator. */
    public RetryExecutor(RetryPolicy policy) {
        this(policy, new NoopResponseValidator());
    }

    public RetryExecutor(RetryPolicy policy, ResponseValidator validator) {
        this.policy    = policy;
        this.validator = validator;
    }

    @FunctionalInterface
    public interface Call {
        RestResponse run() throws Exception;
    }

    // S3776: a retry state machine — the four outcomes (transient-failure retry, status-based retry,
    // network-error retry, non-retryable rethrow) read most clearly inline; extracting them would force
    // sentinel returns across method boundaries. Delay/retry-after/sleep are already factored out.
    /** What says whether a 2xx is data — read by the caller too, which redacts a refused body more (#1393). */
    public ResponseValidator validator() {
        return validator;
    }

    @SuppressWarnings("java:S3776")
    public RestResponse execute(Call call) throws Exception {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                RestResponse r = call.run();
                try {
                    validator.validate(r);
                } catch (TransientFailureException tfe) {
                    lastEx = tfe;
                    if (attempt == policy.maxAttempts()) throw tfe;
                    sleepFor(computeDelay(attempt, 0));
                    continue;
                }
                if (!policy.retryOn().contains(r.status())) return r;
                if (attempt == policy.maxAttempts()) return r;
                sleepFor(computeDelay(attempt, retryAfterMillis(r.header("Retry-After"), Instant.now())));
            } catch (IOException | InterruptedException ex) {
                lastEx = ex;
                if (!policy.retriesNetworkFailure(ex) || attempt == policy.maxAttempts()) throw ex;
                sleepFor(computeDelay(attempt, -1));
            } catch (Exception ex) {
                throw ex;
            }
        }
        if (lastEx != null) throw lastEx;
        throw new IllegalStateException("unreachable");
    }

    /**
     * The wait a {@code Retry-After} header asks, in milliseconds: a number of seconds, or an HTTP date (RFC 9110
     * §10.2.3) read against {@code now} — a date already past is an immediate retry. {@code -1} when the header is
     * absent or reads as neither, which leaves the backoff to decide.
     */
    static long retryAfterMillis(Optional<String> header, Instant now) {
        if (header.isEmpty()) return -1L;
        String value = header.get().trim();
        try {
            return Long.parseLong(value) * 1000L;
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
                return Math.max(0L, Duration.between(now, at).toMillis());
            } catch (DateTimeException notADate) {
                return -1L;
            }
        }
    }

    private long computeDelay(int attempt, long retryAfterMs) {
        if (retryAfterMs >= 0) return retryAfterMs;
        long base = switch (policy.backoff()) {
            case "exponential" -> (long) (policy.initialDelay().toMillis() * Math.pow(2, attempt - 1.0));
            case "linear"      -> policy.initialDelay().toMillis() * attempt;
            case "constant"    -> policy.initialDelay().toMillis();
            default            -> policy.initialDelay().toMillis();
        };
        base = Math.min(base, policy.maxDelay().toMillis());
        double j = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * policy.jitter();
        return (long) (base * j);
    }

    private void sleepFor(long ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }
}
