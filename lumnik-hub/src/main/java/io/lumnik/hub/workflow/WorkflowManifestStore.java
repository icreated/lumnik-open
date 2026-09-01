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

    /**
     * The name of another workflow already declaring this exact (table, column) for this tenant.
     * A column has exactly one lifecycle: a rename must not leave two contradictory declared
     * graphs over the same column, both retrieved, the chat forced to choose between them.
     */
    public Optional<String> findOtherOnColumn(long tenantId, String table, String column, String name) {
        String sql = "SELECT name FROM core.workflow_manifest "
            + "WHERE tenant_id = ? AND on_table = ? AND on_column = ? AND name <> ? ORDER BY name LIMIT 1";
        return Jdbc.queryOne(adminDs, "WorkflowManifestStore.findOtherOnColumn", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, table);
            ps.setString(3, column);
            ps.setString(4, name);
        }, rs -> rs.getString(1));
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
