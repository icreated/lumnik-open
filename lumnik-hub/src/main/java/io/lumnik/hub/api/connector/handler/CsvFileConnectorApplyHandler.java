package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.validator.JsonNodes;
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
    private static final String PARSER = "parser";
    private static final String MAPPING = "mapping";
    private static final String INDEXES = "indexes";
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
        // Absent means absent in both spellings YAML has for it — a missing key, and `parser:` with
        // nothing after it — which is ConfigDefRunner's own predicate, the one that let the manifest
        // through. And removing rather than skipping is what makes DELETING the block a gesture
        // instead of a no-op: this map is seeded from the connector's existing config, so a guard
        // that only ever puts would keep yesterday's parser forever. Both pinned by
        // CsvFileConnectorApplyHandlerIT, which owns the reasoning.
        JsonNode parser = spec.path(PARSER);
        if (parser.isMissingNode() || parser.isNull()) {
            connCfg.remove(PARSER);
        } else {
            connCfg.put(PARSER, JSON.convertValue(parser, Map.class));
        }
        if (!spec.path(MAPPING).isMissingNode()) {
            connCfg.put(MAPPING, JSON.convertValue(spec.path(MAPPING), Map.class));
        }
        // `parser`'s door, one block over: `indexes` is read out of `spec.target`, which this change
        // also made omittable. Deliberately the SAME four lines as above, down to the predicate —
        // the first version of this asked `target.has(INDEXES)`, which answers true for a key whose
        // value is null, so `indexes:` written empty walked past a guard standing three lines under
        // the one that refuses exactly that. Presence is not value. Identical shapes are what keep a
        // reader from having to notice the difference.
        JsonNode indexes = spec.path("target").path(INDEXES);
        if (indexes.isMissingNode() || indexes.isNull()) {
            connCfg.remove(INDEXES);
        } else {
            connCfg.put(INDEXES, JSON.convertValue(indexes, List.class));
        }
        if (!spec.path(AFTER_PROCESS).isMissingNode()) {
            connCfg.put(AFTER_PROCESS, spec.path(AFTER_PROCESS).asText());
        }
        // blankOrNull here and NOT above: this field is a string, and `.asText()` on a NullNode
        // returns the literal "null" — a path named that. The helper cannot serve `parser` or
        // `indexes`, because ObjectNode.asText() is "" and it would call every present block blank.
        // Three guards, two predicates, each matched to what its node actually is.
        JsonNode archivePath = spec.path(ARCHIVE_PATH);
        if (JsonNodes.blankOrNull(archivePath)) {
            connCfg.remove(ARCHIVE_PATH);
        } else {
            connCfg.put(ARCHIVE_PATH, archivePath.asText());
        }
        return connCfg;
    }
}
