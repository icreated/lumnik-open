package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persists kind:Workflow manifests (per-tenant; RLS on the app DS, tenant bound here on admin). */
@ApplicationScoped
public class WorkflowManifestStore {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    public record StoredWorkflow(String name, String scope, String table, String column,
                                 boolean enabled, String yaml) {}

    /**
     * Upsert this workflow, refusing to overwrite a name the caller's grants do not reach.
     *
     * <p>{@code callerScopes} is an authorization predicate, not a filter ({@code null} = unscoped
     * caller, tenant-wide); {@link ScopeGuardedUpsert} says why the door's earlier check is not
     * enough on its own, and do not "simplify" this one away on the grounds that the door already
     * asked. Re-homing a lifecycle takes its chunk and its observation ledger with it.
     *
     * <p>One statement, so autocommit IS the unit of work. A
     * {@code uq_workflow_manifest_tenant_column} violation is not a zero-row outcome — it throws,
     * and the door unwraps it by SQLSTATE.
     */
    public ScopeGuardedUpsert upsert(long tenantId, WorkflowManifest m, String yamlText,
                                     Set<String> callerScopes) {
        // The predicate reads the EXISTING row — which in a DO UPDATE is the one spelled with the
        // table name, `excluded` being the row we proposed.
        String sql = "INSERT INTO core.workflow_manifest "
            + "(tenant_id, name, yaml_text, scope, on_table, on_column) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text, "
            + "scope = EXCLUDED.scope, on_table = EXCLUDED.on_table, on_column = EXCLUDED.on_column, "
            + "updated_at = NOW() "
            + "WHERE (?::text[] IS NULL OR core.workflow_manifest.scope = ANY (?::text[]))";
        return ScopeGuardedUpsert.of(Jdbc.update(adminDs, "WorkflowManifestStore.upsert", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, m.name());
            ps.setString(3, yamlText);
            ps.setString(4, m.scope());
            ps.setString(5, m.table());
            ps.setString(6, m.column());
            ScopeGuardedUpsert.bindGrants(ps, 7, callerScopes);
        }));
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

    /**
     * Removes one workflow for good — the manifest AND its ledger. Existence is the caller's
     * question, answered by findScope.
     *
     * <p>Neither {@code core.workflow_observation} nor {@code core.workflow_transition} carries
     * a FK/cascade to {@code core.workflow_manifest} (V33's own comment on the schema), so a
     * manifest-only delete would strand them: a name reapplied later would inherit the deleted
     * lifecycle's stale {@code since}/{@code alerted_at} and transition tape (review round on
     * #507). One connection, one transaction — {@link Jdbc} is deliberately not for this shape
     * (its own javadoc): a partial purge (manifest gone, ledger stranded, or the reverse) would
     * be worse than deleting nothing.
     */
    public void delete(long tenantId, String name) {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try {
                deleteFrom(c, "core.workflow_manifest", "name", tenantId, name);
                deleteFrom(c, "core.workflow_observation", "workflow_name", tenantId, name);
                deleteFrom(c, "core.workflow_transition", "workflow_name", tenantId, name);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("WorkflowManifestStore.delete: " + e, e);
        }
    }

    private static void deleteFrom(Connection c, String table, String nameColumn,
                                   long tenantId, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + table + " WHERE tenant_id = ? AND " + nameColumn + " = ?")) {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    /**
     * Flips the {@code enabled} column. Already fully wired: {@code WorkflowRegistry.all} filters
     * on it and {@code WorkflowDormancySweep} only iterates enabled lifecycles — this is the hand
     * that was missing to reach the switch (#507).
     *
     * <p>V32's {@code uq_workflow_manifest_tenant_column} javadoc-in-SQL says "there is no path
     * that ever sets it false" — this is that path, and the constraint's own plain-UNIQUE choice
     * still stands: a disabled lifecycle still occupies its (table, column), on purpose, so a
     * rename cannot slip past the one-lifecycle-per-column rule just by disabling the original
     * first. The migration comment is now stale prose, not a stale rule — left as-is rather than
     * edited, since editing a shipped migration's bytes breaks Flyway's checksum validation on
     * every environment that already ran it.
     */
    public void setEnabled(long tenantId, String name, boolean enabled) {
        String sql = "UPDATE core.workflow_manifest SET enabled = ?, updated_at = NOW() "
            + "WHERE tenant_id = ? AND name = ?";
        Jdbc.update(adminDs, "WorkflowManifestStore.setEnabled", sql, ps -> {
            ps.setBoolean(1, enabled);
            ps.setLong(2, tenantId);
            ps.setString(3, name);
        });
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
