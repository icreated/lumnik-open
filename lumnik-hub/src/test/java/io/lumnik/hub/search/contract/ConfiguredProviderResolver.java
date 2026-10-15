package io.lumnik.hub.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.ProjectionSink;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;

/** Pure, non-CDI resolution of {@code lumnik.search.provider} against a supplied
 *  {@code Instance<IndexedSearchPort>} — extracted specifically so it can be unit-tested against
 *  {@code lumnik-testkit}'s CDI mocks instead of the live CDI container inside
 *  {@code SearchProviderContractIT} (#674). */
public final class ConfiguredProviderResolver {

    public record Resolved(IndexedSearchPort port, ProjectionSink sink, String name) {}

    private ConfiguredProviderResolver() {}

    public static Resolved resolve(Instance<IndexedSearchPort> providers, String configuredName) {
        List<IndexedSearchPort> matches = new ArrayList<>();
        for (IndexedSearchPort p : providers) {
            if (configuredName.equals(nameOf(p))) matches.add(p);
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("no provider found for configured name: " + configuredName);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("more than one provider reports the configured name: " + configuredName);
        }
        IndexedSearchPort port = matches.get(0);
        if (!(port instanceof ProjectionSink sink)) {
            throw new IllegalStateException("provider '" + configuredName + "' (" + port.getClass().getName()
                + ") does not implement ProjectionSink — the SPI requires the same concrete class to implement both");
        }
        return new Resolved(port, sink, configuredName);
    }

    /** Mirrors {@code SearchGate.nameOf} — a provider whose {@code name()} throws or answers blank
     *  must never match a configured name here either, exactly as it never matches in production. */
    private static String nameOf(IndexedSearchPort p) {
        try {
            String name = p.name();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
