package io.lumnik.hub.ask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Level 1 of discovery: build a SchemaCard for a scope purely from the ingested-table
 * registry (connector.dynamic_table). No source contact — works for every source family.
 * Column labels default to the raw column name; AD enrichment (level 2) overlays later.
 */
@ApplicationScoped
public class RegistrySchemaBuilder {

    private static final Logger LOG = Logger.getLogger(RegistrySchemaBuilder.class);
    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

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
        List<UUID> connectorIds = new ArrayList<>();
        String connSql =
            "SELECT id FROM connector.connector " +
            "WHERE tenant_id = ? AND ? = ANY(tags) AND deleted_at IS NULL";
        try (Connection cx = adminDs.getConnection();
             PreparedStatement ps = cx.prepareStatement(connSql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) connectorIds.add((UUID) rs.getObject("id"));
            }
        } catch (Exception e) {
            throw new RuntimeException("RegistrySchemaBuilder.resolve(connectors): " + e.getMessage(), e);
        }
        if (connectorIds.isEmpty()) return List.of();

        List<PendingTable> pending = new ArrayList<>();
        Set<String> seenFqn = new HashSet<>();
        // A table another connector also writes holds that connector's rows too (#1186): it enters
        // the card only when every connector writing it carries the scope.
        String dtSql =
            "SELECT table_schema, table_name, schema_inferred::text AS schema_json " +
            "FROM connector.dynamic_table dt WHERE tenant_id = ? AND connector_id = ANY(?) " +
            "AND NOT EXISTS (SELECT 1 FROM connector.dynamic_table o " +
            "LEFT JOIN connector.connector oc ON oc.id = o.connector_id AND oc.tenant_id = o.tenant_id " +
            "WHERE o.tenant_id = dt.tenant_id AND o.table_schema = dt.table_schema " +
            "AND o.table_name = dt.table_name AND (? = ANY(oc.tags)) IS NOT TRUE)";
        try (Connection cx = adminDs.getConnection();
             PreparedStatement ps = cx.prepareStatement(dtSql)) {
            ps.setLong(1, tenantId);
            ps.setArray(2, cx.createArrayOf("uuid", connectorIds.toArray()));
            ps.setString(3, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schema = rs.getString("table_schema");
                    String tableName = rs.getString("table_name");
                    if (!seenFqn.add(schema + "." + tableName)) {
                        LOG.warnf("duplicate hub table %s.%s for scope %s — keeping first", schema, tableName, scope);
                        continue;
                    }
                    Map<String,String> types =
                        M.readValue(rs.getString("schema_json"), new TypeReference<LinkedHashMap<String,String>>() {});
                    // Source entity for level-2 AD enrichment = the source table, i.e. the hub
                    // table name without the "t_" dump prefix (t_c_order → c_order).
                    String sourceEntity = tableName.startsWith("t_") ? tableName.substring(2) : tableName;
                    pending.add(new PendingTable(schema, tableName, types, sourceEntity));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("RegistrySchemaBuilder.resolve(dynamic_table): " + e.getMessage(), e);
        }
        if (pending.isEmpty()) return List.of();

        // jsonb canonicalises object keys by length then bytes, so each `types`' own iteration
        // order is NOT the source's field order (#521). The physical table — created in that
        // order by #496's fix — is; reorder against it, one query for every table in the scope.
        Map<String, List<String>> physicalOrder = physicalColumnOrders(pending);
        List<HubTable> out = new ArrayList<>();
        for (PendingTable p : pending) {
            List<String> ordered = physicalOrder.getOrDefault(p.schema() + "." + p.table(), List.of());
            out.add(new HubTable(p.schema(), p.table(), inSourceFieldOrder(ordered, p.types()), p.sourceEntity()));
        }
        return out;
    }

    private record PendingTable(String schema, String table, Map<String,String> types, String sourceEntity) {}

    /** Every table's physical column order, keyed by {@code schema.table} — one round trip. */
    private Map<String, List<String>> physicalColumnOrders(List<PendingTable> tables) {
        String inClause = tables.stream().map(t -> "(?,?)").collect(Collectors.joining(","));
        String sql = "SELECT table_schema, table_name, column_name FROM information_schema.columns " +
                     "WHERE (table_schema, table_name) IN (" + inClause + ") " +
                     "ORDER BY table_schema, table_name, ordinal_position";
        List<String[]> rows = Jdbc.query(adminDs, "RegistrySchemaBuilder.columnOrders", sql,
            ps -> {
                int i = 1;
                for (PendingTable t : tables) { ps.setString(i++, t.schema()); ps.setString(i++, t.table()); }
            },
            rs -> new String[]{rs.getString("table_schema"), rs.getString("table_name"), rs.getString("column_name")});

        Map<String, List<String>> byTable = new LinkedHashMap<>();
        for (String[] r : rows) byTable.computeIfAbsent(r[0] + "." + r[1], k -> new ArrayList<>()).add(r[2]);
        return byTable;
    }

    /**
     * {@code types} reordered against the physical table's column order
     * ({@code information_schema.columns.ordinal_position}) — the physical truth, needing no
     * storage of its own. Falls back to {@code types}' own order for a name the physical table
     * doesn't (yet) carry, so a stale or synthetic registry row still yields every known column.
     */
    private Map<String,String> inSourceFieldOrder(List<String> physicalOrder, Map<String,String> types) {
        Map<String,String> cols = new LinkedHashMap<>();
        for (String name : physicalOrder) {
            String type = types.get(name);
            if (type != null) cols.put(name, type);
        }
        types.forEach(cols::putIfAbsent);
        return cols;
    }
}
