package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/** Persists kind:Process manifests (per-tenant; RLS on the app DS, tenant bound here on admin). */
@ApplicationScoped
public class ProcessManifestStore {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private final ProcessManifestParser parser = new ProcessManifestParser();

    public record StoredProcess(String name, String scope, String onTable, List<String> when,
                                String emitType, String emitMessage, boolean enabled) {}

    public void upsert(long tenantId, ProcessManifest m, String yamlText) {
        String sql = "INSERT INTO core.process_manifest "
            + "(tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, name) DO UPDATE SET yaml_text = EXCLUDED.yaml_text, "
            + "scope = EXCLUDED.scope, on_table = EXCLUDED.on_table, emit_type = EXCLUDED.emit_type, "
            + "updated_at = NOW()";
        Jdbc.update(adminDs, "ProcessManifestStore.upsert", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, m.name());
            ps.setString(3, yamlText);
            ps.setString(4, m.scope());
            ps.setString(5, m.onTable());
            ps.setString(6, m.emitType());
        });
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

    /** Removes one process for good. Existence is the caller's question, answered by findScope. */
    public void delete(long tenantId, String name) {
        String sql = "DELETE FROM core.process_manifest WHERE tenant_id = ? AND name = ?";
        Jdbc.update(adminDs, "ProcessManifestStore.delete", sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
        });
    }

    /**
     * Flips the {@code enabled} column — the switch that decided whether a process fires and had
     * no hand to reach it. Like {@link #delete}, existence is already the caller's question.
     */
    public void setEnabled(long tenantId, String name, boolean enabled) {
        String sql = "UPDATE core.process_manifest SET enabled = ?, updated_at = NOW() "
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
                    m.emitType(), m.emitMessage(), rs.getBoolean(2));
        });
    }
}
