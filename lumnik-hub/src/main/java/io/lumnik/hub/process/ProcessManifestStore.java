package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persists kind:Process manifests (per-tenant; RLS on the app DS, tenant bound here on admin). */
@ApplicationScoped
public class ProcessManifestStore {

    private static final Logger LOG = Logger.getLogger(ProcessManifestStore.class);

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private final ProcessManifestParser parser = new ProcessManifestParser();

    public record StoredProcess(String name, String scope, String onTable, List<String> when,
                                String emitType, String emitMessage, List<String> emitFields, String emitKey,
                                boolean enabled) {}

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

    /** {@code LIKE} for an entity view's name: the prefix's underscore is a wildcard, so it is escaped. */
    private static final String ENTITY_VIEW_LIKE = EntityViewCompiler.VIEW_PREFIX.replace("_", "\\_") + "%";

    /**
     * The tenant's enabled processes whose trigger is a fused entity view ({@code connector.v_*}, #1833): the
     * rows a change in one of the Entity's sources can reach.
     */
    public List<StoredProcess> findEnabledOnEntityViews(long tenantId) {
        return query("SELECT yaml_text, enabled FROM core.process_manifest "
                   + "WHERE tenant_id = ? AND enabled AND on_table LIKE ?",
                ps -> { ps.setLong(1, tenantId); ps.setString(2, ENTITY_VIEW_LIKE); });
    }

    /** The entity views the tenant's enabled processes listen on — no manifest read: the emission gate asks it per batch. */
    public List<String> enabledEntityViews(long tenantId) {
        return Jdbc.query(adminDs, "ProcessManifestStore.enabledEntityViews",
                "SELECT DISTINCT on_table FROM core.process_manifest WHERE tenant_id = ? AND enabled AND on_table LIKE ?",
                ps -> { ps.setLong(1, tenantId); ps.setString(2, ENTITY_VIEW_LIKE); }, rs -> rs.getString(1));
    }

    /** Every tenant's enabled entity views: the background index pass is not a request, it has no tenant. */
    public List<String> allEnabledEntityViews() {
        return Jdbc.query(adminDs, "ProcessManifestStore.allEnabledEntityViews",
                "SELECT DISTINCT on_table FROM core.process_manifest WHERE enabled AND on_table LIKE ?",
                ps -> ps.setString(1, ENTITY_VIEW_LIKE), rs -> rs.getString(1));
    }

    /** Does any tenant's enabled process listen on this entity view? */
    public boolean anyEnabledOn(String view) {
        return Jdbc.exists(adminDs, "ProcessManifestStore.anyEnabledOn",
                "SELECT 1 FROM core.process_manifest WHERE enabled AND on_table = ?", ps -> ps.setString(1, view));
    }

    private List<StoredProcess> query(String sql, Jdbc.Binder binder) {
        // A stored manifest that cannot be read (written by another version) is left out and said: one broken process must not
        // take the others of its table with it, and the sweeper moves on whatever a consumer throws.
        return Jdbc.query(adminDs, "ProcessManifestStore.query", sql, binder, rs -> {
            try {
                return Optional.of(stored(rs.getString(1), rs.getBoolean(2)));
            } catch (RuntimeException e) {
                // the parser's words quote the text it choked on, and a manifest can hold customer data: the kind only
                LOG.errorf("a stored process cannot be read (%s) — it is left out", e.getClass().getSimpleName());
                LOG.debug("stored process read failure", e);
                return Optional.<StoredProcess>empty();
            }
        }).stream().flatMap(Optional::stream).toList();
    }

    /** A stored manifest's yaml, read as the process it declares. */
    StoredProcess stored(String yaml, boolean enabled) {
        ProcessManifest m = parser.parse(yaml);
        return new StoredProcess(m.name(), m.scope(), m.onTable(), m.when(),
                m.emitType(), m.emitMessage(), m.emitFields(), m.emitKey(), enabled);
    }
}
