package io.lumnik.hub.ask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.connector.mapping.writer.DriftDetector;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Level 1 of discovery: build a SchemaCard for a scope purely from the ingested-table
 * registry (connector.dynamic_table). No source contact — works for every source family.
 * Column labels default to the raw column name; AD enrichment (level 2) overlays later.
 */
@ApplicationScoped
public class RegistrySchemaBuilder {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject DriftDetector driftDetector;

    /** A resolved hub table + its source entity (for level-2 AD lookup). */
    public record HubTable(String schema, String name, Map<String,String> columns, String sourceEntity) {}

    /** Build the level-1 card. Never throws on "no data"; returns an empty card. */
    public SchemaCard build(long tenantId, String scope) {
        List<HubTable> hubs = resolve(tenantId, scope);
        List<SchemaCard.Table> tables = new ArrayList<>();
        for (HubTable h : hubs) {
            List<SchemaCard.Column> cols = new ArrayList<>();
            h.columns().forEach((col, pgType) ->
                cols.add(new SchemaCard.Column(col, pgType, col))); // label = raw name
            tables.add(new SchemaCard.Table(h.schema() + "." + h.name(), h.name(), cols));
        }
        return new SchemaCard(scope, tables);
    }

    /**
     * Resolve scope → ingested hub tables. Connectors carry the scope in {@code tags}; each hub
     * table in {@code connector.dynamic_table} carries its owning {@code connector_id} (set by the
     * writer). So scope → connectors(by tag) → their hub tables — model-agnostic.
     * Exposed for level-2 (needs sourceEntity per table).
     */
    public List<HubTable> resolve(long tenantId, String scope) {
        String connSql =
            "SELECT id FROM connector.connector " +
            "WHERE tenant_id = ? AND ? = ANY(tags) AND deleted_at IS NULL";
        List<UUID> connectorIds = Jdbc.query(adminDs, "RegistrySchemaBuilder.resolve(connectors)", connSql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
        }, rs -> (UUID) rs.getObject("id"));
        if (connectorIds.isEmpty()) return List.of();

        // A table another connector also writes holds that connector's rows too (#1186): it enters
        // the card only when every connector writing it carries the scope.
        String dtSql =
            "SELECT table_schema, table_name, schema_inferred::text AS schema_json " +
            "FROM connector.dynamic_table dt WHERE tenant_id = ? AND connector_id = ANY(?) " +
            "AND NOT EXISTS (SELECT 1 FROM connector.dynamic_table o " +
            "LEFT JOIN connector.connector oc ON oc.id = o.connector_id AND oc.tenant_id = o.tenant_id " +
            "WHERE o.tenant_id = dt.tenant_id AND o.table_schema = dt.table_schema " +
            "AND o.table_name = dt.table_name AND (? = ANY(oc.tags)) IS NOT TRUE)";
        List<PendingTable> rows = Jdbc.query(adminDs, "RegistrySchemaBuilder.resolve(dynamic_table)", dtSql, ps -> {
            ps.setLong(1, tenantId);
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", connectorIds.toArray()));
            ps.setString(3, scope);
        }, rs -> {
            String tableName = rs.getString("table_name");
            Map<String,String> types =
                M.readValue(rs.getString("schema_json"), new TypeReference<LinkedHashMap<String,String>>() {});
            // Source entity for level-2 AD enrichment = the source table, i.e. the hub
            // table name without the "t_" dump prefix (t_c_order → c_order).
            String sourceEntity = tableName.startsWith("t_") ? tableName.substring(2) : tableName;
            return new PendingTable(rs.getString("table_schema"), tableName, types, sourceEntity);
        });
        // #2023: each registration holds the columns its own mapping brought, so a tenant's table is the UNION of its
        // registrations' names. The table, not the registry, says their order and current type: another tenant's
        // widening reaches this card too. It never adds a name: another tenant's column stays out.
        Map<String, PendingTable> merged = new LinkedHashMap<>();
        for (PendingTable p : rows) {
            merged.merge(p.schema() + "." + p.table(), p, (a, b) -> {
                Map<String, String> types = new LinkedHashMap<>(a.types());
                b.types().forEach(types::putIfAbsent);
                return new PendingTable(a.schema(), a.table(), types, a.sourceEntity());
            });
        }
        List<HubTable> out = new ArrayList<>();
        for (PendingTable p : merged.values()) {
            Map<String, String> physical = driftDetector.actualSchema(p.schema(), p.table());
            out.add(new HubTable(p.schema(), p.table(), current(physical, p.types()), p.sourceEntity()));
        }
        return out;
    }

    /**
     * The registered names, in the table's column order and with the table's current type; a name the table does not
     * (yet) hold keeps its registered type, so a stale or synthetic registry row still yields every known column.
     */
    private static Map<String, String> current(Map<String, String> physical, Map<String, String> registered) {
        Map<String, String> cols = new LinkedHashMap<>();
        if (physical != null) {
            physical.forEach((name, type) -> {
                if (registered.containsKey(name)) cols.put(name, type);
            });
        }
        registered.forEach(cols::putIfAbsent);
        return cols;
    }

    private record PendingTable(String schema, String table, Map<String,String> types, String sourceEntity) {}


}
