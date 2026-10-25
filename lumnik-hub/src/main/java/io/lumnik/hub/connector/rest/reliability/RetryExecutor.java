package io.lumnik.hub.connector.rest.reliability;

import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.RestResponse;
import java.io.IOException;
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
                sleepFor(computeDelay(attempt, retryAfterMillis(r)));
            } catch (IOException | InterruptedException ex) {
                lastEx = ex;
                if (!policy.retryOnNetwork() || attempt == policy.maxAttempts()) throw ex;
                sleepFor(computeDelay(attempt, -1));
            } catch (Exception ex) {
                throw ex;
            }
        }
        if (lastEx != null) throw lastEx;
        throw new IllegalStateException("unreachable");
    }

    private long retryAfterMillis(RestResponse r) {
        Optional<String> ra = r.header("Retry-After");
        if (ra.isEmpty()) return -1L;
        try {
            return Long.parseLong(ra.get().trim()) * 1000L;
        } catch (NumberFormatException e) {
            return -1L;
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
