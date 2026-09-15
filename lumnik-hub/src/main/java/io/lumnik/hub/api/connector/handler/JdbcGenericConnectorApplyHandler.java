package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.validator.jdbc.TableDefaultsMerger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@ConnectorTypeId("jdbc-generic")
public class JdbcGenericConnectorApplyHandler implements ConnectorApplyHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String KIND_JDBC_GENERIC = "jdbc-generic";
    private static final String DEFAULTS = "defaults";

    @Inject ConnectorRepository connRepo;
    @Inject ConnectorEndpointRepository epRepo;

    @Override public String kind() { return KIND_JDBC_GENERIC; }

    @Override
    @SuppressWarnings("unchecked")
    public ApplyResult apply(JsonNode doc, long tenant) {
        JsonNode meta = doc.path("metadata");
        String name = meta.path("name").asText();
        JsonNode spec = doc.path("spec");

        Connector conn = ApplyUpsert.connector(connRepo, tenant, name, KIND_JDBC_GENERIC, meta, spec,
            c -> buildConnectorConfig(c, spec));

        int applied = 0;
        JsonNode defaults = spec.path(DEFAULTS);
        JsonNode tables = spec.path("source").path("tables");
        if (tables.isArray()) {
            for (JsonNode tableNode : tables) {
                String tableName = tableNode.path("name").asText();
                if (tableName == null || tableName.isBlank()) continue;

                JsonNode merged = TableDefaultsMerger.merge(defaults, tableNode);
                Map<String, Object> epCfg = JSON.convertValue(merged, Map.class);
                ApplyUpsert.endpoint(epRepo, conn, tenant, tableName, "GET", "/" + tableName, epCfg);
                applied++;
            }
        }

        return new ApplyResult(name, conn.getId(), applied);
    }

    @SuppressWarnings("unchecked") // JSON.convertValue(..., Map.class) — raw Map is the Jackson contract here
    private Map<String, Object> buildConnectorConfig(Connector conn, JsonNode spec) {
        Map<String, Object> connCfg = new HashMap<>(conn.getConfig() != null ? conn.getConfig() : Map.of());
        connCfg.put("kind", KIND_JDBC_GENERIC);
        connCfg.put("connection", JSON.convertValue(spec.path("connection"), Map.class));
        ApplyUpsert.putOrRemove(connCfg, DEFAULTS, spec.path(DEFAULTS), Map.class);
        return connCfg;
    }
}
