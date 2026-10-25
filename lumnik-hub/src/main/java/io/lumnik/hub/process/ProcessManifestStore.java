package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persists kind:Process manifests (per-tenant; RLS on the app DS, tenant bound here on admin). */
@ApplicationScoped
public class ProcessManifestStore {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private final ProcessManifestParser parser = new ProcessManifestParser();

    public record StoredProcess(String name, String scope, String onTable, List<String> when,
                                String emitType, String emitMessage, List<String> emitFields, boolean enabled) {}

    /**
     * Upsert this process, refusing to overwrite a name the caller's grants do not reach.
     *
     * <p>{@code callerScopes} is an authorization predicate, not a filter ({@code null} = unscoped
     * caller, tenant-wide); {@link ScopeGuardedUpsert} says why the door's earlier check is not
     * enough on its own, and do not "simplify" this one away on the grounds that the door already
     * asked. Re-homing a process takes its emitted text with it.
     *
     * <p>One statement, so autocommit IS the unit of work.
     */
    public ScopeGuardedUpsert upsert(long tenantId, ProcessManifest m, String yamlText,
                                     Set<String> callerScopes) {
        // The predicate reads the EXISTING row — which in a DO UPDATE is the one spelled with the
        // table name, `excluded` being the row we proposed.
        String sql = "INSERT INTO core.process_manifest "
            + "(tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text, "
            + "scope = EXCLUDED.scope, on_table = EXCLUDED.on_table, emit_type = EXCLUDED.emit_type, "
            + "updated_at = NOW(), revision = nextval('core.process_manifest_revision_seq') "
            + "WHERE (?::text[] IS NULL OR core.process_manifest.scope = ANY (?::text[]))";
        return ScopeGuardedUpsert.of(Jdbc.update(adminDs, "ProcessManifestStore.upsert", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, m.name());
            ps.setString(3, yamlText);
            ps.setString(4, m.scope());
            ps.setString(5, m.onTable());
            ps.setString(6, m.emitType());
            ScopeGuardedUpsert.bindGrants(ps, 7, callerScopes);
        }));
    }

    /**
     * The scope one process was declared in, if it exists. Read BEFORE the yaml so the scope
     * boundary can refuse without the caller ever touching a manifest they may not hold.
     */
    public Optional<String> findScope(long tenantId, String name) {
        String sql = "SELECT scope FROM core.process_manifest WHERE tenant_id = ? AND name = ?";
        return Jdbc.queryOne(adminDs, "ProcessManifestStore.findScope", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
        }, rs -> rs.getString(1));
    }

    /** The stored manifest yaml for one process, if it exists — the TUI edit round-trip. */
    public Optional<String> findYaml(long tenantId, String name) {
        String sql = "SELECT yaml_text FROM core.process_manifest WHERE tenant_id = ? AND name = ?";
        return Jdbc.queryOne(adminDs, "ProcessManifestStore.findYaml", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
        }, rs -> rs.getString(1));
    }

    /** Removes one process for good; true when this call removed it. Existence is the caller's
     *  question, answered by findScope. */
    public boolean delete(long tenantId, String name) {
        String sql = "DELETE FROM core.process_manifest WHERE tenant_id = ? AND name = ?";
        return Jdbc.update(adminDs, "ProcessManifestStore.delete", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
        }) > 0;
    }

    /**
     * Flips the {@code enabled} column — the switch that decided whether a process fires and had
     * no hand to reach it. Like {@link #delete}, existence is already the caller's question.
     */
    public void setEnabled(long tenantId, String name, boolean enabled) {
        String sql = "UPDATE core.process_manifest SET enabled = ?, updated_at = NOW(), "
            + "revision = nextval('core.process_manifest_revision_seq') "
            + "WHERE tenant_id = ? AND name = ?";
        Jdbc.update(adminDs, "ProcessManifestStore.setEnabled", sql, ps -> {
            ps.setBoolean(1, enabled);
            ps.setLong(2, tenantId);
            ps.setString(3, name);
        });
    }

    public List<StoredProcess> listByTenant(long tenantId) {
        return query("SELECT yaml_text, enabled FROM core.process_manifest WHERE tenant_id = ? ORDER BY name",
                ps -> ps.setLong(1, tenantId));
    }

    /**
     * The tenant's processes as one value (#1351): whether any is enabled, and the fingerprint of
     * their revisions — every apply, re-apply, enable and disable takes a fresh one from a sequence,
     * and a removal drops one, so two equal fingerprints mean no process changed in between. No
     * clock is involved: the database orders the revisions.
     */
    public ProcessSet processSet(long tenantId) {
        return Jdbc.queryOne(adminDs, "ProcessManifestStore.processSet",
                "SELECT COALESCE(bool_or(enabled), false), " + FINGERPRINT
                    + " FROM core.process_manifest WHERE tenant_id = ?",
                ps -> ps.setLong(1, tenantId), rs -> new ProcessSet(rs.getBoolean(1), rs.getString(2))).orElseThrow();
    }

    public record ProcessSet(boolean anyEnabled, String fingerprint) {}

    /**
     * The fingerprint over {@code core.process_manifest} rows of one tenant — one expression, so the
     * run queue's start (a single UPDATE) computes exactly what {@link #processSet} does.
     */
    public static final String FINGERPRINT =
            "md5(COALESCE(string_agg(revision::text, ',' ORDER BY revision), ''))";

    /** Enabled processes listening on this table — the ProcessConsumer's per-event lookup. */
    public List<StoredProcess> findEnabledFor(long tenantId, String table) {
        return query("SELECT yaml_text, enabled FROM core.process_manifest "
                   + "WHERE tenant_id = ? AND on_table = ? AND enabled",
                ps -> { ps.setLong(1, tenantId); ps.setString(2, table); });
    }

    private List<StoredProcess> query(String sql, Jdbc.Binder binder) {
        return Jdbc.query(adminDs, "ProcessManifestStore.query", sql, binder, rs -> {
            ProcessManifest m = parser.parse(rs.getString(1));
            return new StoredProcess(m.name(), m.scope(), m.onTable(), m.when(),
                    m.emitType(), m.emitMessage(), m.emitFields(), rs.getBoolean(2));
        });
    }
}
