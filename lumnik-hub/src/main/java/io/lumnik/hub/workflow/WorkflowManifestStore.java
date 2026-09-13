package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/** Persists kind:Workflow manifests (per-tenant; RLS on the app DS, tenant bound here on admin). */
@ApplicationScoped
public class WorkflowManifestStore {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    public record StoredWorkflow(String name, String scope, String table, String column,
                                 boolean enabled, String yaml) {}

    public void upsert(long tenantId, WorkflowManifest m, String yamlText) {
        String sql = "INSERT INTO core.workflow_manifest "
            + "(tenant_id, name, yaml_text, scope, on_table, on_column) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text, "
            + "scope = EXCLUDED.scope, on_table = EXCLUDED.on_table, on_column = EXCLUDED.on_column, "
            + "updated_at = NOW()";
        Jdbc.update(adminDs, "WorkflowManifestStore.upsert", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, m.name());
            ps.setString(3, yamlText);
            ps.setString(4, m.scope());
            ps.setString(5, m.table());
            ps.setString(6, m.column());
        });
    }

    /** Another workflow already declaring a (table, column), and the scope it was declared in. */
    public record ColumnOwner(String name, String scope) {}

    /**
     * The workflow already declaring this exact (table, column) for this tenant, if any.
     * A column has exactly one lifecycle: a rename must not leave two contradictory declared
     * graphs over the same column, both retrieved, the chat forced to choose between them.
     *
     * <p>Deliberately NOT scope-filtered — the rule is one lifecycle per column for the TENANT,
     * so a foreign owner must still stop the apply. Its SCOPE rides along because the refusal's
     * wording depends on it: the owner's name is what makes the message actionable, and it is a
     * name from another métier's card when the caller does not hold that scope (#516). Deciding
     * that is the door's job; handing it what it needs to decide is this method's.
     */
    public Optional<ColumnOwner> findOtherOnColumn(long tenantId, String table, String column, String name) {
        String sql = "SELECT name, scope FROM core.workflow_manifest "
            + "WHERE tenant_id = ? AND on_table = ? AND on_column = ? AND name <> ? ORDER BY name LIMIT 1";
        return Jdbc.queryOne(adminDs, "WorkflowManifestStore.findOtherOnColumn", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, table);
            ps.setString(3, column);
            ps.setString(4, name);
        }, rs -> new ColumnOwner(rs.getString(1), rs.getString(2)));
    }

    /**
     * The scope one workflow was declared in, if it exists. Read BEFORE the yaml so the scope
     * boundary can refuse without the caller ever touching a manifest they may not hold.
     */
    public Optional<String> findScope(long tenantId, String name) {
        String sql = "SELECT scope FROM core.workflow_manifest WHERE tenant_id = ? AND name = ?";
        return Jdbc.queryOne(adminDs, "WorkflowManifestStore.findScope", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
        }, rs -> rs.getString(1));
    }

    /**
     * Does this tenant declare ANY enabled lifecycle in this scope? A yes/no, answered by the
     * database — the abstention signpost needs no graph.
     *
     * <p>Deliberately NOT {@code WorkflowRegistry.all(tenant)}: that is the SERVING path, and its
     * invariant is that every read re-parses the stored YAML ("Do not memoize this"). Asking it a
     * boolean costs a SELECT of every yaml_text plus a full parse per row, all discarded but one
     * bit. The denormalized {@code scope} column exists for lookups exactly like this one — which
     * is also why the registry's warning not to trust {@code on_table}/{@code on_column} does not
     * apply here: nothing is being served, only counted.
     */
    public boolean anyInScope(long tenantId, String scope) {
        String sql = "SELECT 1 FROM core.workflow_manifest "
            + "WHERE tenant_id = ? AND scope = ? AND enabled LIMIT 1";
        return Jdbc.queryOne(adminDs, "WorkflowManifestStore.anyInScope", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, scope);
        }, rs -> rs.getInt(1)).isPresent();
    }

    /** The stored manifest yaml for one workflow, if it exists — the TUI edit round-trip. */
    public Optional<String> findYaml(long tenantId, String name) {
        String sql = "SELECT yaml_text FROM core.workflow_manifest WHERE tenant_id = ? AND name = ?";
        return Jdbc.queryOne(adminDs, "WorkflowManifestStore.findYaml", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
        }, rs -> rs.getString(1));
    }

    public List<StoredWorkflow> listByTenant(long tenantId) {
        String sql = "SELECT name, scope, on_table, on_column, enabled, yaml_text "
                   + "FROM core.workflow_manifest WHERE tenant_id = ? ORDER BY name";
        return Jdbc.query(adminDs, "WorkflowManifestStore.listByTenant", sql,
            ps -> ps.setLong(1, tenantId),
            rs -> new StoredWorkflow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getBoolean(5), rs.getString(6)));
    }
}
