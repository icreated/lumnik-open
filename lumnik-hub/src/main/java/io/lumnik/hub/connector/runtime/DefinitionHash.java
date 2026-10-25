package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.audit.CanonicalHash;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.EffectiveConfig;

/**
 * The witness of the definition a queued run was accepted under (#952, D10): SHA-256 of the canonical
 * JSON of what the endpoint runs under ({@link EffectiveConfig}, which already answers that question
 * for the run itself). No mapping is hashed separately: the mapping a run executes is built from this
 * same config. The door computes it at acceptance; the
 * dispatcher recomputes it before the run may start. The credential's VALUE is not in it: the run
 * reads the secret at execution.
 */
final class DefinitionHash {

    private DefinitionHash() {}

    static String of(Connector c, ConnectorEndpoint endpoint) {
        return CanonicalHash.of(EffectiveConfig.of(c, endpoint));
    }
}
