package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.rest.pagination.preset.PaginationPresetRegistry;
import io.lumnik.hub.validator.JsonNodes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@ConnectorTypeId("rest-generic")
public class RestConnectorApplyHandler implements ConnectorApplyHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String KIND_REST_GENERIC = "rest-generic";
    private static final String BASE_URL = "base_url";
    private static final String HEADERS = "headers";

    @Inject ConnectorRepository connRepo;
    @Inject ConnectorEndpointRepository epRepo;
    @Inject PaginationPresetRegistry presets;

    @Override public String kind() { return KIND_REST_GENERIC; }

    @Override
    public ApplyResult apply(JsonNode doc, long tenant) {
        JsonNode meta = doc.path("metadata");
        String name = meta.path("name").asText();
        JsonNode spec = doc.path("spec");

        String typeId = meta.path("connector_type").asText(KIND_REST_GENERIC);
        Connector conn = ApplyUpsert.connector(connRepo, tenant, name, typeId, meta, spec,
            c -> buildConnectorConfig(c, meta, spec));

        int applied = applyEndpoints(conn, tenant, spec.path("endpoints"));
        return new ApplyResult(name, conn.getId(), applied);
    }

    @SuppressWarnings("unchecked") // JSON.convertValue(..., Map.class) — raw Map is the Jackson contract here
    private Map<String, Object> buildConnectorConfig(Connector conn, JsonNode meta, JsonNode spec) {
        Map<String, Object> connCfg = new HashMap<>(conn.getConfig() != null ? conn.getConfig() : Map.of());
        connCfg.put("kind", meta.path("connector_type").asText(KIND_REST_GENERIC));
        if (!spec.path(BASE_URL).isMissingNode()) {
            connCfg.put(BASE_URL, spec.path(BASE_URL).asText());
        }
        if (!spec.path("auth").isMissingNode()) {
            connCfg.put("auth", JSON.convertValue(spec.path("auth"), Map.class));
        }
        if (!spec.path("defaults").isMissingNode()) {
            spec.path("defaults").fields().forEachRemaining(
                e -> connCfg.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        }
        // Only a real, non-blank string — an explicit null would persist NullNode.asText() == "null"
        // and build base_url + "null" at runtime.
        String graphqlPath = JsonNodes.textOrNull(spec.path("graphql_path"));
        if (graphqlPath != null) {
            connCfg.put("graphql_path", graphqlPath);
        }
        // The runtime rate limiter reads this off the connector config (RestEndpointReader);
        // without the copy the validated spec-level knob silently falls back to 100/s.
        String rateLimit = JsonNodes.textOrNull(spec.path("default_rate_limit"));
        if (rateLimit != null) {
            connCfg.put("default_rate_limit", rateLimit);
        }
        if (!spec.path(HEADERS).isMissingNode()) {
            connCfg.put(HEADERS, JSON.convertValue(spec.path(HEADERS), List.class));
        }
        return connCfg;
    }

    private int applyEndpoints(Connector conn, long tenant, JsonNode endpoints) {
        if (!endpoints.isArray()) return 0;
        int applied = 0;
        for (JsonNode epNode : endpoints) {
            String epName = epNode.path("id").asText();
            if (epName == null || epName.isBlank()) continue;
            upsertEndpoint(conn, tenant, epName, epNode);
            applied++;
        }
        return applied;
    }

    @SuppressWarnings("unchecked") // JSON.convertValue(..., Map.class) — raw Map is the Jackson contract here
    private void upsertEndpoint(Connector conn, long tenant, String epName, JsonNode epNode) {
        Map<String, Object> epCfg = new HashMap<>();
        epNode.fields().forEachRemaining(e -> {
            if (List.of("id", "method", "path").contains(e.getKey())) return;
            if ("pagination".equals(e.getKey())) {
                epCfg.put("pagination", JSON.convertValue(presets.expand(e.getValue()), Map.class));
            } else {
                epCfg.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class));
            }
        });

        ApplyUpsert.endpoint(epRepo, conn, tenant, epName,
            epNode.path("method").asText("GET"), epNode.path("path").asText(), epCfg);
    }
}
