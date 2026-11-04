package io.lumnik.hub.connector.rest.reliability;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterRegistryTest {

    private final RateLimiterRegistry registry = new RateLimiterRegistry();
    private final UUID connector = UUID.randomUUID();

    @Test
    void the_same_spec_is_the_same_limiter() {
        // One budget for every endpoint of a connector: this sharing is the registry's job.
        assertSame(registry.forConnector(connector, "10/s"), registry.forConnector(connector, "10/s"));
    }

    /** Padding is not a new rate: replacing the limiter would refill its bucket and allow a burst. */
    @Test
    void a_spec_that_differs_only_by_padding_is_the_same_limiter() {
        assertSame(registry.forConnector(connector, "10/s"), registry.forConnector(connector, " 10/s "));
    }

    /** The manifest is read on every run: a re-applied rate must not wait for a restart to count. */
    @Test
    void a_changed_spec_is_a_new_limiter() {
        RateLimiter before = registry.forConnector(connector, "100/s");
        RateLimiter after = registry.forConnector(connector, "5/s");
        assertNotSame(before, after);
        assertSame(after, registry.forConnector(connector, "5/s"));
    }

    @Test
    void another_connectors_limiter_is_left_alone() {
        UUID other = UUID.randomUUID();
        RateLimiter theirs = registry.forConnector(other, "100/s");
        registry.forConnector(connector, "100/s");
        registry.forConnector(connector, "5/s");
        assertSame(theirs, registry.forConnector(other, "100/s"));
    }

    @Test
    void a_spec_that_does_not_parse_fails_and_keeps_the_current_limiter() {
        RateLimiter current = registry.forConnector(connector, "10/s");
        assertThrows(IllegalArgumentException.class, () -> registry.forConnector(connector, "ten per second"));
        assertSame(current, registry.forConnector(connector, "10/s"));
    }
}
