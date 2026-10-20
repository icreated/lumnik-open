package io.lumnik.hub.export;

import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-bound kind:Export manifests. Unlike SavedViewStore/AppRegistrationStore (deployment-
 * global, admin datasource), this table carries a real tenant_id + RLS, so every method runs on
 * the request's own JTA-enlisted connection (via Session.doReturningWork) instead of a fresh
 * admin one — the connection TenantConnectionInterceptor already stamped with
 * SET LOCAL app.current_tenant for this transaction.
 *
 * <p><b>The closer sibling is WorkflowManifestStore</b> (core.workflow_manifest, V32), not
 * SavedViewStore: it is tenant-bound too, with the same ScopeGuardedUpsert + findScope(tenantId,
 * name) + ON CONFLICT (tenant_id, name) shape, over a table that also declares RLS. It reaches
 * that table through the ADMIN datasource with an explicit tenant_id in every WHERE — which means
 * RLS is bypassed and the tenant filter is a thing each query must remember. This store diverges
 * deliberately: running the scope-guarded statement on the request's own RLS-enforced connection
 * makes tenant isolation a property of the connection rather than of the SQL, so the same
 * statement the scope guard re-asks atomically is the one the database is enforcing the tenant on.
 * ExportManifestTenantIsolationIT is what turns that into a proven guarantee rather than a claim —
 * a test WorkflowManifestStore's approach cannot have at all, since under BYPASSRLS there is
 * nothing left for such a test to measure.
 *
 * <p>Every method is {@code @Transactional(MANDATORY)}: the invariant used to be prose, and a
 * violation failed misleadingly rather than loudly — with no transaction, SET LOCAL never fires,
 * the RLS policy's NULLIF guard turns the empty GUC into NULL, and the upsert writes zero rows
 * (read back as a scope refusal) while the reads come back empty (read back as a 404). MANDATORY
 * turns that silent mis-diagnosis into a TransactionRequiredException at the call site.
 */
@ApplicationScoped
public class ExportManifestStore {

    /** {@code id} survives re-applies (the upsert keeps the row), so it keys RunLock and export_run. */
    public record Row(UUID id, long tenantId, String name, String scope, String entityName,
                       String destinationKind, String schedule, String yaml,
                       Instant createdAt) {}

    public record Summary(String name, String scope, String entityName, String destinationKind,
                           String schedule) {}

    @Inject EntityManager em;

    private Session session() {
        return em.unwrap(Session.class);
    }

    /**
     * Upsert this export, refusing to overwrite a name the caller's grants do not reach — same
     * scope-guarded shape SavedViewStore.upsert uses. tenant_id is bound explicitly (never
     * inferred from the conflict target) and the conflict target names (tenant_id, name)
     * explicitly, matching the table's real uniqueness. Because RLS already restricts which row a
     * same-name conflict can even reach to the caller's own tenant, cross-tenant collision cannot
     * happen structurally — this guard only has to defend the same-tenant, different-scope
     * re-homing race ScopeGuardedUpsert exists for.
     *
     * <p>{@code name} is lowercased here, not merely trusted to already be lowercase —
     * {@code ExportApplyResource} normalizes before calling this, but {@link #find}/
     * {@link #findScope} do the same on read, so a caller of {@code GET /api/exports/{name}} who
     * types the manifest's original casing still resolves the row. Same belt-and-braces posture
     * {@code SavedViewStore} already uses; the store owns the normalization, not each caller.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public ScopeGuardedUpsert upsert(long tenantId, String name, String scope, String entityName,
                                      String destinationKind, String schedule, String yaml,
                                      Set<String> callerScopes) {
        String key = name.toLowerCase(Locale.ROOT);
        return session().doReturningWork(conn -> ScopeGuardedUpsert.of(Jdbc.update(conn,
                "ExportManifestStore.upsert",
                "INSERT INTO connector.export_manifest "
              + "(tenant_id, name, scope, entity_name, destination_kind, schedule, yaml_text) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?) "
              + "ON CONFLICT (tenant_id, name) DO UPDATE SET "
              + "scope = excluded.scope, entity_name = excluded.entity_name, "
              + "destination_kind = excluded.destination_kind, schedule = excluded.schedule, "
              + "yaml_text = excluded.yaml_text, updated_at = now() "
              + "WHERE (?::text[] IS NULL OR connector.export_manifest.scope = ANY (?::text[]))",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, key);
                    ps.setString(3, scope);
                    ps.setString(4, entityName);
                    ps.setString(5, destinationKind);
                    if (schedule == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, schedule);
                    ps.setString(7, yaml);
                    ScopeGuardedUpsert.bindGrants(ps, 8, callerScopes);
                })));
    }

    /** The scope of the row this (tenant, name) addresses, or empty when the name is free — the
     *  apply door's re-homing check (ApplyScopeBoundary). Lowercased like {@link #upsert}, so a
     *  name reaching this from the door's own already-lowercased manifest.name() costs nothing
     *  and a name reaching it from anywhere else still resolves. */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<String> findScope(long tenantId, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return session().doReturningWork(conn -> Jdbc.queryOne(conn, "ExportManifestStore.findScope",
                "SELECT scope FROM connector.export_manifest WHERE tenant_id = ? AND name = ?",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, key);
                },
                rs -> rs.getString(1)));
    }

    /** Lowercased like {@link #upsert} — the fix for GET /api/exports/{name} resolving
     *  regardless of the caller's casing, since ExportRegistryResource passes the raw path
     *  parameter through untouched. */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<Row> find(long tenantId, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return session().doReturningWork(conn -> Jdbc.queryOne(conn, "ExportManifestStore.find",
                "SELECT id, tenant_id, name, scope, entity_name, destination_kind, schedule, yaml_text, "
              + "created_at "
              + "FROM connector.export_manifest WHERE tenant_id = ? AND name = ?",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, key);
                },
                rs -> new Row(rs.getObject(1, UUID.class), rs.getLong(2),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), rs.getString(8), Jdbc.instant(rs, 9))));
    }

    /** scopes == null -> all (unscoped caller); empty set -> none (bound to nothing). Always
     *  tenant-scoped, belt-and-braces alongside RLS (the SecretRepository precedent). */
    @Transactional(Transactional.TxType.MANDATORY)
    public List<Summary> list(long tenantId, Set<String> scopes) {
        if (scopes != null && scopes.isEmpty()) return List.of();
        return session().doReturningWork(conn -> {
            String sql = "SELECT name, scope, entity_name, destination_kind, schedule "
                + "FROM connector.export_manifest WHERE tenant_id = ?"
                + (scopes == null ? "" : " AND scope = ANY (?)")
                + " ORDER BY name";
            return Jdbc.query(conn, "ExportManifestStore.list", sql,
                ps -> {
                    ps.setLong(1, tenantId);
                    if (scopes != null) ps.setArray(2, conn.createArrayOf("text", scopes.toArray()));
                },
                rs -> new Summary(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)));
        });
    }
}
