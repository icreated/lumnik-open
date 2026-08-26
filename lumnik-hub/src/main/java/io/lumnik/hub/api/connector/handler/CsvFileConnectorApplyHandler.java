package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@ConnectorTypeId("csv-file")
public class CsvFileConnectorApplyHandler implements ConnectorApplyHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String KIND_CSV_FILE = "csv-file";
    private static final String TRANSPORT = "transport";
    private static final String MAPPING = "mapping";
    private static final String INDEXES = "indexes";
    private static final String SCHEDULE = "schedule";
    private static final String AFTER_PROCESS = "after_process";
    private static final String ARCHIVE_PATH = "archive_path";

    @Inject ConnectorRepository connRepo;
    @Inject ConnectorEndpointRepository epRepo;

    @Override public String kind() { return KIND_CSV_FILE; }

    @Override
    public ApplyResult apply(JsonNode doc, long tenant) {
        JsonNode meta = doc.path("metadata");
        String name = meta.path("name").asText();
        JsonNode spec = doc.path("spec");

        Connector conn = ApplyUpsert.connector(connRepo, tenant, name, KIND_CSV_FILE, meta, spec,
            c -> buildConnectorConfig(c, spec));

        String path = spec.path(TRANSPORT).path("path").asText();
        String table = Identifiers.slug(name, "table");
        ApplyUpsert.endpoint(epRepo, conn, tenant, "default", "GET", path, Map.of("name", table));

        return new ApplyResult(name, conn.getId(), 1);
    }

    @SuppressWarnings("unchecked") // JSON.convertValue(..., Map.class) — raw Map is the Jackson contract here
    private Map<String, Object> buildConnectorConfig(Connector conn, JsonNode spec) {
        Map<String, Object> connCfg = new HashMap<>(conn.getConfig() != null ? conn.getConfig() : Map.of());
        connCfg.put("kind", KIND_CSV_FILE);
        connCfg.put(TRANSPORT, JSON.convertValue(spec.path(TRANSPORT), Map.class));
        connCfg.put("parser", JSON.convertValue(spec.path("parser"), Map.class));
        if (!spec.path(MAPPING).isMissingNode()) {
            connCfg.put(MAPPING, JSON.convertValue(spec.path(MAPPING), Map.class));
        }
        JsonNode target = spec.path("target");
        if (target.has(INDEXES)) {
            connCfg.put(INDEXES, JSON.convertValue(target.get(INDEXES), List.class));
        }
        if (!spec.path(SCHEDULE).isMissingNode()) {
            connCfg.put(SCHEDULE, spec.path(SCHEDULE).asText());
        }
        if (!spec.path(AFTER_PROCESS).isMissingNode()) {
            connCfg.put(AFTER_PROCESS, spec.path(AFTER_PROCESS).asText());
        }
        if (!spec.path(ARCHIVE_PATH).isMissingNode()) {
            connCfg.put(ARCHIVE_PATH, spec.path(ARCHIVE_PATH).asText());
        }
        return connCfg;
    }
}
