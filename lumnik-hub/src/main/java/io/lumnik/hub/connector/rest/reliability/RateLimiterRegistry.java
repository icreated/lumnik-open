package io.lumnik.hub.connector.rest.reliability;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class RateLimiterRegistry {

    /** {@code count/window}: a count, a slash, an optional window size, and a unit. */
    private static final Pattern SPEC = Pattern.compile("(\\d+)\\s*/\\s*(\\d*)\\s*([smh])");

    /** Beyond this the count is not a rate anyone meant, and parsing it would overflow. */
    private static final int MAX_DIGITS = 9;

    private static final String SHAPE = "expected \"count/window\" with unit s, m or h — "
        + "e.g. \"100/s\" or \"10/2m\"";

    private final ConcurrentHashMap<UUID, RateLimiter> byConnector = new ConcurrentHashMap<>();

    public RateLimiter forConnector(UUID connectorId, String spec) {
        return byConnector.computeIfAbsent(connectorId, id -> parse(spec));
    }

    /**
     * What is wrong with this {@code default_rate_limit}, or empty if nothing is.
     *
     * <p>The runtime's own reading, exposed so {@code RestConnectorValidator} can refuse the manifest
     * with the same words instead of re-deciding the grammar. The field used to reach {@link #parse}
     * unvalidated, and every malformed shape below surfaced as a raw crash on the first request of a
     * run — except a negative count, which surfaced as nothing at all.
     */
    public static Optional<String> describeProblem(String spec) {
        if (spec == null || spec.isBlank()) return Optional.of("must not be blank; " + SHAPE);

        Matcher m = SPEC.matcher(spec.trim());
        if (!m.matches()) return Optional.of("must be a rate; " + SHAPE + " (got \"" + spec + "\")");

        if (m.group(1).length() > MAX_DIGITS) return Optional.of("count is implausibly large");
        if (Integer.parseInt(m.group(1)) < 1) return Optional.of("count must be at least 1");

        String window = m.group(2);
        if (!window.isEmpty()) {
            if (window.length() > MAX_DIGITS) return Optional.of("window is implausibly large");
            if (Integer.parseInt(window) < 1) return Optional.of("window must be at least 1");
        }
        return Optional.empty();
    }

    static RateLimiter parse(String spec) {
        describeProblem(spec).ifPresent(problem -> {
            throw new IllegalArgumentException("default_rate_limit " + problem);
        });

        Matcher m = SPEC.matcher(spec.trim());
        m.matches();                                     // already proven by describeProblem
        int count = Integer.parseInt(m.group(1));
        int size = m.group(2).isEmpty() ? 1 : Integer.parseInt(m.group(2));

        Duration window = switch (m.group(3).charAt(0)) {
            case 's' -> Duration.ofSeconds(size);
            case 'm' -> Duration.ofMinutes(size);
            case 'h' -> Duration.ofHours(size);
            default  -> throw new IllegalStateException("unit outside the pattern: " + m.group(3));
        };
        return new RateLimiter(count, window);
    }
}
