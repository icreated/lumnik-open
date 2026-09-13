package io.lumnik.hub.connector.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * The config an endpoint actually runs under — connection-level merged with endpoint-level.
 *
 * <p>It lives here, on the pair of entities it is a property of, because two callers must not
 * answer it differently. The ingestion run has always built it; the endpoint pre-flight used to
 * read {@code connector.getConfig()} raw, and so saw neither the {@code connection} block nor
 * anything declared on the endpoint — no {@code pagination}, no per-endpoint override, and on a
 * manifest with an explicit connection block, no {@code base_url} either. A pre-flight that
 * knocks on a different door than the run answers a question nobody asked.
 */
public final class EffectiveConfig {

    private EffectiveConfig() {}

    /**
     * The connection alone, for a caller holding a connector and no endpoint — the REST probe,
     * which samples a path that has no endpoint row yet.
     */
    public static Map<String, Object> of(Connector c) {
        return stampCredential(c, new HashMap<>(connectionOf(c)));
    }

    /** The connection, with the endpoint's own config laid over it — the endpoint wins on conflicts. */
    public static Map<String, Object> of(Connector c, ConnectorEndpoint endpoint) {
        Map<String, Object> config = new HashMap<>(connectionOf(c));
        if (endpoint.getConfig() != null) config.putAll(endpoint.getConfig());
        return stampCredential(c, config);
    }

    /**
     * The connection block: {@code config.get("connection")} when the connector carries an explicit
     * one (declarative manifests), otherwise the whole flat connector config (legacy / synthetic
     * endpoints).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> connectionOf(Connector c) {
        return (c.getConfig().get("connection") instanceof Map<?, ?> conn)
            ? (Map<String, Object>) conn
            : c.getConfig();
    }

    /**
     * Stamped LAST, after any endpoint overlay: {@code _credentialId} is not a key an endpoint may
     * declare. It is read back off this map to resolve a stored secret, so letting an endpoint's
     * config blob overwrite it would let that blob choose which credential a run presents — and the
     * flat endpoint CRUD persists such a blob without connector-type validation.
     */
    private static Map<String, Object> stampCredential(Connector c, Map<String, Object> config) {
        if (c.getCredentialId() != null) config.put("_credentialId", c.getCredentialId().toString());
        return config;
    }
}
