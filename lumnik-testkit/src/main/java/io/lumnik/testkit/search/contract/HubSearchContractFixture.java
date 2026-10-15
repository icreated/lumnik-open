package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.ProjectionSink;

/** {@code provider}/{@code sink} pairing (same {@code name()}, {@code sink} genuinely implements
 *  {@link ProjectionSink}) is enforced by the caller at discovery time — see
 *  {@code ConfiguredProviderResolver} — never inside this record. */
public record HubSearchContractFixture(
    IndexedSearchPort provider,
    ProjectionSink sink,
    HubSearchContractDriver driver
) {}
