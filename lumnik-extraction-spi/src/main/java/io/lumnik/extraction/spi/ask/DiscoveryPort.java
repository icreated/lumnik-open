package io.lumnik.extraction.spi.ask;

import java.util.Optional;

/**
 * Boundary the schema REST resource depends on. The impl (DiscoveryService) lives
 * in lumnik-hub because it needs named datasources + connector repositories.
 * Injected via Instance&lt;DiscoveryPort&gt; so rag builds/tests without it (mirrors AskPort).
 */
public interface DiscoveryPort {
    /** Build + persist the SchemaCard for (tenant, scope); idempotent. */
    DiscoveryResult rediscover(long tenantId, String scope);

    /** Load the persisted SchemaCard, if any. */
    Optional<SchemaCard> load(long tenantId, String scope);
}
