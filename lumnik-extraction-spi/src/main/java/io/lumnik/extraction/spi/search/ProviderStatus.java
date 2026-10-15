package io.lumnik.extraction.spi.search;

import java.util.Objects;

/**
 * A provider's own view of its health. {@code reason} is a short machine-readable token
 * ({@code backend_unreachable}, …), required when degraded; never a message carrying data.
 */
public record ProviderStatus(SearchAvailability state, String reason, String version) {

    public ProviderStatus {
        Objects.requireNonNull(state, "state");
        if (state == SearchAvailability.UNAVAILABLE) {
            throw new IllegalArgumentException("UNAVAILABLE means not installed; an installed provider reports DEGRADED");
        }
        if (state == SearchAvailability.DEGRADED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("a degraded status needs a reason");
        }
    }

    /** Pre-#703 shape — every existing call site (no provider reports a version yet). */
    public ProviderStatus(SearchAvailability state, String reason) {
        this(state, reason, null);
    }

    public static ProviderStatus available() {
        return new ProviderStatus(SearchAvailability.AVAILABLE, null, null);
    }

    public static ProviderStatus degraded(String reason) {
        return new ProviderStatus(SearchAvailability.DEGRADED, reason, null);
    }
}
