package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.csv.MappingTypeDrift;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.json.JsonNodes;
import io.lumnik.hub.connector.naming.Identifiers;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.validator.ValidationError;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
@ConnectorTypeId("csv-file")
public class CsvFileConnectorApplyHandler implements ConnectorApplyHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String KIND_CSV_FILE = "csv-file";
    private static final String TRANSPORT = "transport";
    private static final String PARSER = "parser";
    private static final String MAPPING = "mapping";
    private static final String INDEXES = "indexes";
    private static final String AFTER_PROCESS = "after_process";
    private static final String REFRESH = "refresh";
    private static final String ARCHIVE_PATH = "archive_path";

    @Inject ConnectorRepository connRepo;
    @Inject ConnectorEndpointRepository epRepo;
    @Inject ConnectorCursorEntityRepository cursorRepo;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Override public String kind() { return KIND_CSV_FILE; }

    @Override
    public ApplyResult apply(JsonNode doc, long tenant) {
        JsonNode meta = doc.path("metadata");
        String name = meta.path("name").asText();
        JsonNode spec = doc.path("spec");

        Object mappingBefore = connRepo.byAddressForUpdate(tenant, name).map(c -> mappingOf(c)).orElse(null);
        Connector conn = ApplyUpsert.connector(connRepo, tenant, name, KIND_CSV_FILE, meta, spec,
            c -> buildConnectorConfig(c, spec));
        defaultEndpoint(epRepo, conn);

        // #1882: the files an earlier run read were read through another mapping. The unchanged-skip answers for the
        // bytes, not for what the mapping makes of them: drop the cursor, the next run reads them again.
        if (!Objects.equals(mappingBefore, mappingOf(conn))) {
            epRepo.findByName(conn.getId(), "default").ifPresent(ep -> {
                var cursor = cursorRepo.findById(ep.getId());
                if (cursor != null) cursorRepo.delete(cursor);
            });
        }
        return new ApplyResult(name, conn.getId(), 1, typeDrift(conn));
    }

    private static Object mappingOf(Connector c) {
        return c.getConfig() == null ? null : c.getConfig().get(MAPPING);
    }

    /** What the mapping types that the table, already born, holds as another type (#1882). */
    @SuppressWarnings("unchecked")
    private List<ValidationError> typeDrift(Connector conn) {
        if (!(mappingOf(conn) instanceof Map<?, ?> mapping)) return List.of();
        String table = "t_" + Identifiers.slug(conn.getName(), "table");
        Map<String, String> held = new HashMap<>();
        Jdbc.query(adminDs, "CsvFileConnectorApplyHandler.typeDrift",
            "SELECT column_name, udt_name FROM information_schema.columns WHERE table_schema = 'connector'"
                + " AND table_name = ?",
            ps -> ps.setString(1, table), rs -> held.put(rs.getString(1), rs.getString(2)));
        // t_* is shared between tenants: its types are this caller's to read only once the table holds their rows.
        if (held.isEmpty() || !Jdbc.queryOne(adminDs, "CsvFileConnectorApplyHandler.typeDrift.rows",
                "SELECT 1 FROM connector.\"" + table + "\" WHERE tenant_id = ? LIMIT 1",
                ps -> ps.setLong(1, conn.getTenantId()), rs -> 1).isPresent()) return List.of();
        return MappingTypeDrift.warnings("connector." + table, (Map<String, Object>) mapping, held);
    }

    /**
     * The one endpoint a csv-file connector runs: {@code default}, writing the table named after the
     * connector. Both connector doors write it — this one and the flat {@code POST
     * /api/platform/connectors} — so a connector created either way runs the same (#1189). The
     * endpoint carries the table name in its config and {@code transport.path} as its path; parser,
     * mapping and the rest are read from the connector's config.
     */
    public static void defaultEndpoint(ConnectorEndpointRepository epRepo, Connector conn) {
        String path = conn.getConfig() != null && conn.getConfig().get(TRANSPORT) instanceof Map<?, ?> t
            && t.get("path") != null ? String.valueOf(t.get("path")) : "";
        String table = Identifiers.slug(conn.getName(), "table");
        ApplyUpsert.endpoint(epRepo, conn, conn.getTenantId(), "default", "GET", path, Map.of("name", table));
    }

    @SuppressWarnings("unchecked") // JSON.convertValue(..., Map.class) — raw Map is the Jackson contract here
    private Map<String, Object> buildConnectorConfig(Connector conn, JsonNode spec) {
        Map<String, Object> connCfg = new HashMap<>(conn.getConfig() != null ? conn.getConfig() : Map.of());
        connCfg.put("kind", KIND_CSV_FILE);
        connCfg.put(TRANSPORT, JSON.convertValue(spec.path(TRANSPORT), Map.class));
        // The three omittable blocks of this kind. ApplyUpsert.putOrRemove owns why the gesture is
        // shaped the way it is; what is local to here is that `indexes` is read out of `spec.target`
        // and stored FLAT — the block is the manifest's shape, not the config's.
        ApplyUpsert.putOrRemove(connCfg, PARSER, spec.path(PARSER), Map.class);
        ApplyUpsert.putOrRemove(connCfg, MAPPING, spec.path(MAPPING), Map.class);
        ApplyUpsert.putOrRemove(connCfg, INDEXES, spec.path("target").path(INDEXES), List.class);
        ApplyUpsert.putOrRemove(connCfg, REFRESH, spec.path(REFRESH), Map.class);
        if (!spec.path(AFTER_PROCESS).isMissingNode()) {
            connCfg.put(AFTER_PROCESS, spec.path(AFTER_PROCESS).asText());
        }
        // blankOrNull and NOT putOrRemove: this field is a string. JsonNodes.blankOrNull's javadoc
        // says why the two predicates do not substitute for each other in either direction.
        JsonNode archivePath = spec.path(ARCHIVE_PATH);
        if (JsonNodes.blankOrNull(archivePath)) {
            connCfg.remove(ARCHIVE_PATH);
        } else {
            connCfg.put(ARCHIVE_PATH, archivePath.asText());
        }
        return connCfg;
    }
}
