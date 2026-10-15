package io.lumnik.extraction.spi.search;

/**
 * Direct business-data search over an optional index (#649/#668). Distinct from the chunk-oriented
 * RAG {@code SearchPort} and from the entity-resolution {@code CandidateProvider}: the three may share
 * a backend, never a contract.
 *
 * <p>A provider never decides who may see what. The hub resolves the caller's tenant and the entities
 * it may read, and hands both over in every {@link IndexedSearchQuery}; the provider enforces them
 * inside the backend query itself — an unauthorized hit is never fetched, so never post-filtered.
 *
 * <p>No implementation on the classpath is the normal state of the Open edition and of a standard
 * Pro installation: the hub then reports Search as not installed, never as an empty result.
 */
public interface IndexedSearchPort {

    /** Stable provider identifier ({@code postgres}, {@code opensearch}, …), selectable by configuration. */
    String name();

    /**
     * Whether this provider can answer right now. Cheap and side-effect free — the hub asks it on
     * capability discovery. {@link SearchAvailability#UNAVAILABLE} is the hub's word for "not
     * installed" and is not a valid answer here; report an unreachable backend as degraded.
     */
    ProviderStatus status();

    /**
     * Ranked hits for one query, restricted to {@code query.tenantId()} and
     * {@code query.allowedEntities()} by the backend itself.
     *
     * @throws IndexedSearchException if the provider cannot answer; never answer an outage with zero hits.
     */
    IndexedSearchResult search(IndexedSearchQuery query) throws IndexedSearchException;
}
