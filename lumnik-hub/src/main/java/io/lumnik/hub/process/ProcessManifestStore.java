package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
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
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, m.name());
            ps.setString(3, yamlText);
            ps.setString(4, m.scope());
            ps.setString(5, m.onTable());
            ps.setString(6, m.emitType());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("ProcessManifestStore.upsert: " + e.getMessage(), e);
        }
    }

    /**
     * The scope one process was declared in, if it exists. Read BEFORE the yaml so the scope
     * boundary can refuse without the caller ever touching a manifest they may not hold.
     */
    public Optional<String> findScope(long tenantId, String name) {
        String sql = "SELECT scope FROM core.process_manifest WHERE tenant_id = ? AND name = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("ProcessManifestStore.findScope: " + e.getMessage(), e);
        }
    }

    /** The stored manifest yaml for one process, if it exists — the TUI edit round-trip. */
    public Optional<String> findYaml(long tenantId, String name) {
        String sql = "SELECT yaml_text FROM core.process_manifest WHERE tenant_id = ? AND name = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("ProcessManifestStore.findYaml: " + e.getMessage(), e);
        }
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

    private interface Binder { void bind(PreparedStatement ps) throws Exception; }

    private List<StoredProcess> query(String sql, Binder binder) {
        List<StoredProcess> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ProcessManifest m = parser.parse(rs.getString(1));
                    out.add(new StoredProcess(m.name(), m.scope(), m.onTable(), m.when(),
                            m.emitType(), m.emitMessage(), rs.getBoolean(2)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("ProcessManifestStore.query: " + e.getMessage(), e);
        }
        return out;
    }
}
