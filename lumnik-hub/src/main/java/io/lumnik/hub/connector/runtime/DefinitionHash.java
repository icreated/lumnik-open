package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.EffectiveConfig;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The witness of the definition a queued run was accepted under (#952, D10): SHA-256 of the canonical
 * JSON of what the endpoint runs under ({@link EffectiveConfig}, which already answers that question
 * for the run itself). No mapping is hashed separately: the mapping a run executes is built from this
 * same config. The door computes it at acceptance; the
 * dispatcher recomputes it before the run may start. The credential's VALUE is not in it: the run
 * reads the secret at execution.
 */
final class DefinitionHash {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DefinitionHash() {}

    static String of(Connector c, ConnectorEndpoint endpoint) {
        try {
            return AuditRecorder.sha256(MAPPER.writeValueAsString(
                canonical(MAPPER.convertValue(EffectiveConfig.of(c, endpoint), Object.class))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a definition could not be hashed", e);
        }
    }

    /** Maps sorted by key at every depth (jsonb drops key order); list order is meaningful and kept. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(DefinitionHash::canonical).toList();
        return value;
    }
}
