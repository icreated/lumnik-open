package io.lumnik.hub.ask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

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
    @io.quarkus.agroal.DataSource("admin")
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
     * writer). So scope → connectors(by tag) → their hub tables — model-agnostic, no dependency on
     * the legacy {@code connector_mapping} link. Exposed for level-2 (needs sourceEntity per table).
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

        List<HubTable> out = new ArrayList<>();
        Set<String> seenFqn = new HashSet<>();
        String dtSql =
            "SELECT table_schema, table_name, schema_inferred::text AS schema_json " +
            "FROM connector.dynamic_table WHERE tenant_id = ? AND connector_id = ANY(?)";
        try (Connection cx = adminDs.getConnection();
             PreparedStatement ps = cx.prepareStatement(dtSql)) {
            ps.setLong(1, tenantId);
            ps.setArray(2, cx.createArrayOf("uuid", connectorIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String fqn = rs.getString("table_schema") + "." + tableName;
                    if (!seenFqn.add(fqn)) {
                        LOG.warnf("duplicate hub table %s for scope %s — keeping first", fqn, scope);
                        continue;
                    }
                    Map<String,String> cols =
                        M.readValue(rs.getString("schema_json"), new TypeReference<LinkedHashMap<String,String>>() {});
                    // Source entity for level-2 AD enrichment = the source table, i.e. the hub
                    // table name without the "t_" dump prefix (t_c_order → c_order).
                    String sourceEntity = tableName.startsWith("t_") ? tableName.substring(2) : tableName;
                    out.add(new HubTable(rs.getString("table_schema"), tableName, cols, sourceEntity));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("RegistrySchemaBuilder.resolve(dynamic_table): " + e.getMessage(), e);
        }
        return out;
    }
}
