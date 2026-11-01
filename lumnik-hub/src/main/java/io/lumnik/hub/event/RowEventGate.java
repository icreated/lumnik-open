package io.lumnik.hub.event;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.sql.Array;
import java.sql.Connection;

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
            if (Jdbc.query(c, "RowEventGate webhook endpoints",
                "SELECT event_types, entity_filter FROM core.webhook_endpoint "
                + "WHERE tenant_id = ? AND enabled = TRUE AND archived_at IS NULL AND deleted_at IS NULL",
                ps -> ps.setLong(1, tenantId), rs -> {
                    Array arr = rs.getArray(1);
                    if (arr == null) return false; // event_types is NOT NULL, but never NPE on drift
                    return EntityFilters.passes(rs.getString(2), table) && matchesAny((String[]) arr.getArray());
                }).contains(true)) return true;
            if (Jdbc.query(c, "RowEventGate notification rules",
                "SELECT event_pattern, entity_filter FROM core.notification_rule "
                + "WHERE tenant_id = ? AND enabled = TRUE",
                ps -> ps.setLong(1, tenantId),
                rs -> matchesRowChanged(rs.getString(1)) && EntityFilters.passes(rs.getString(2), table)).contains(true)) return true;
            return Jdbc.exists(c, "RowEventGate process manifests",
                "SELECT 1 FROM core.process_manifest "
                + "WHERE tenant_id = ? AND enabled AND on_table = ? LIMIT 1", ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, table);
                });
        } catch (Exception e) {
            // Fail closed: no events rather than a failed ingestion run. Log so a gate
            // error is visible — a silent swallow would hide a misconfigured subscription path.
            LOG.warnf(e, "RowEventGate: subscription check failed for tenant %d table %s "
                + "— emitting no events", tenantId, table);
            return false;
        }
    }

    private static boolean matchesAny(String[] patterns) {
        for (String p : patterns) if (matchesRowChanged(p)) return true;
        return false;
    }

    /**
     * Does this subscription pattern reach {@code data.row.changed}? Same contract as
     * {@code WebhookEndpoint.matches} / {@code NotificationRule.matches}: exact, or "prefix.*".
     *
     * <p>Public because it is also the question a write door must ask before judging an
     * {@code entity_filter}: only for THIS event family is the entity a table a connector's row
     * writer fills. A {@code decision.dormant.*} rule's entity is the workflow's watched table,
     * which may be a fused view — refusing that on the row-event rule would be a false refusal.
     */
    public static boolean matchesRowChanged(String p) {
        return DATA_ROW_CHANGED.equals(p)
            || (p != null && p.endsWith(".*") && DATA_ROW_CHANGED.startsWith(p.substring(0, p.length() - 2)));
    }
}
