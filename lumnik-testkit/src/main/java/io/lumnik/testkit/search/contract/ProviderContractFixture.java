package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.ProjectionSink;

import java.util.List;

/** {@code providerNativeHostileQueries} supplies QUERY_ABUSE's provider-specific raw-operator
 *  strings (e.g. Postgres tsquery syntax) — everything else QUERY_ABUSE asserts (oversize input,
 *  targeting {@code tenant_id}/an undeclared field) is provider-agnostic and lives in the harness
 *  itself, never in this list. */
public record ProviderContractFixture(
    IndexedSearchPort provider,
    ProjectionSink sink,
    ProviderCapabilities capabilities,
    ProviderFaultController faults,
    List<String> providerNativeHostileQueries
) {}
