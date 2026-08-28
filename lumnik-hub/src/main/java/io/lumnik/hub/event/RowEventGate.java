package io.lumnik.hub.event;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Emission gate for per-row data events: does ANY active subscription on this tenant
 * want data.row.changed for this table? Three subscription sources are consulted — webhook
 * endpoints, notification rules, and kind:Process manifests — per write() batch (cheap: indexed
 * selects per chunk). entity_filter narrows both webhook endpoints and notification rules
 * (shared {@link EntityFilters} contract). Admin DS: the writer runs outside a tenant session.
 */
@ApplicationScoped
public class RowEventGate {

    public static final String DATA_ROW_CHANGED = "data.row.changed";

    private static final Logger LOG =
        Logger.getLogger(RowEventGate.class);

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    public boolean enabled(Long tenantId, String table) {
        try (Connection c = adminDs.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT event_types, entity_filter FROM core.webhook_endpoint "
                    + "WHERE tenant_id = ? AND enabled = TRUE AND archived_at IS NULL AND deleted_at IS NULL")) {
                ps.setLong(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Array arr = rs.getArray(1);
                        if (arr == null) continue; // event_types is NOT NULL, but never NPE on drift
                        String[] types = (String[]) arr.getArray();
                        String filter = rs.getString(2);
                        if (EntityFilters.passes(filter, table) && matchesAny(types)) return true;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT event_pattern, entity_filter FROM core.notification_rule "
                    + "WHERE tenant_id = ? AND enabled = TRUE")) {
                ps.setLong(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (matches(rs.getString(1)) && EntityFilters.passes(rs.getString(2), table)) return true;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM core.process_manifest "
                    + "WHERE tenant_id = ? AND enabled AND on_table = ? LIMIT 1")) {
                ps.setLong(1, tenantId);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return true;
                }
            }
            return false;
        } catch (Exception e) {
            // Fail closed: no events rather than a failed ingestion run. Log so a gate
            // error is visible — a silent swallow would hide a misconfigured subscription path.
            LOG.warnf(e, "RowEventGate: subscription check failed for tenant %d table %s "
                + "— emitting no events", tenantId, table);
            return false;
        }
    }

    private static boolean matchesAny(String[] patterns) {
        for (String p : patterns) if (matches(p)) return true;
        return false;
    }

    /** Same contract as WebhookEndpoint.matches / NotificationRule.matches: exact or "prefix.*". */
    private static boolean matches(String p) {
        return DATA_ROW_CHANGED.equals(p)
            || (p != null && p.endsWith(".*") && DATA_ROW_CHANGED.startsWith(p.substring(0, p.length() - 2)));
    }
}
