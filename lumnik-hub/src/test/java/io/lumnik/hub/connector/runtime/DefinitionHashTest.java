package io.lumnik.hub.connector.runtime;

import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionHashTest {

    private static Connector connector(Map<String, Object> config) {
        Connector c = new Connector();
        c.setConfig(config);
        return c;
    }

    private static ConnectorEndpoint endpoint(Map<String, Object> config) {
        ConnectorEndpoint e = new ConnectorEndpoint();
        e.setConfig(config);
        return e;
    }

    @Test
    void the_order_of_keys_does_not_change_the_hash() {
        // Nested maps are the ones whose order survives (jsonb hands back Jackson's LinkedHashMaps; the top
        // level is re-laid into a HashMap by EffectiveConfig), so the two documents differ in nested order.
        Map<String, Object> xy = new LinkedHashMap<>();
        xy.put("x", 1); xy.put("y", 2);
        Map<String, Object> yx = new LinkedHashMap<>();
        yx.put("y", 2); yx.put("x", 1);
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1); ab.put("b", xy);
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", yx); ba.put("a", 1);

        assertThat(DefinitionHash.of(connector(ab), endpoint(Map.of())))
            .isEqualTo(DefinitionHash.of(connector(ba), endpoint(Map.of())));
    }

    @Test
    void a_change_to_what_the_endpoint_runs_under_changes_the_hash() {
        Connector c = connector(Map.of("url", "jdbc:x"));
        String base = DefinitionHash.of(c, endpoint(Map.of("path", "/a")));

        assertThat(DefinitionHash.of(c, endpoint(Map.of("path", "/b")))).as("endpoint config").isNotEqualTo(base);
        assertThat(DefinitionHash.of(connector(Map.of("url", "jdbc:y")), endpoint(Map.of("path", "/a"))))
            .as("connection config").isNotEqualTo(base);
        Connector withCredential = connector(Map.of("url", "jdbc:x"));
        withCredential.setCredentialId(UUID.randomUUID());
        assertThat(DefinitionHash.of(withCredential, endpoint(Map.of("path", "/a")))).as("which credential").isNotEqualTo(base);
    }

    @Test
    void the_endpoint_wins_a_conflict_as_it_runs() {
        Connector c = connector(Map.of("k", "connector"));
        // EffectiveConfig lays the endpoint over the connection: same effective config, same hash.
        assertThat(DefinitionHash.of(connector(Map.of("k", "old")), endpoint(Map.of("k", "connector"))))
            .isEqualTo(DefinitionHash.of(c, endpoint(Map.of())));
    }

    @Test
    void the_hash_is_a_sha256_hex_digest() {
        assertThat(DefinitionHash.of(connector(Map.of()), endpoint(Map.of()))).matches("[0-9a-f]{64}");
    }
}
