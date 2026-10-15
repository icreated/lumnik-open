package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchPort;

public interface ProviderFaultController {
    AutoCloseable loseBackendDuringQuery();
    AutoCloseable loseBackendDuringIndexing();

    /** Constructs and returns a fresh provider instance in an environment where the backend was
     *  never reachable at all — a property of construction, never of an already-running instance.
     *  Only called when {@link ProviderCapabilities#applicability(ProviderLaneDimension)} for
     *  {@link ProviderLaneDimension#BOOT_ABSENT} returns {@link Applicability.Required}. */
    IndexedSearchPort bootWithBackendAbsent();
}
