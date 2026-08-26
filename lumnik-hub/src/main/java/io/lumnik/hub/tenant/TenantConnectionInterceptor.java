package io.lumnik.hub.tenant;

import io.agroal.api.AgroalPoolInterceptor;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Agroal pool interceptor that issues {@code SET LOCAL app.current_tenant} on every
 * JDBC connection acquired within a JTA transaction.
 *
 * <p>In JTA mode, {@code onConnectionAcquire} fires exactly once per transaction before
 * the connection is associated — which is the correct moment to set {@code SET LOCAL}.
 * The GUC value is scoped to the transaction and automatically reset on commit/rollback,
 * making it safe with connection pooling.
 *
 * <p>In system mode ({@link TenantContext#isSystemMode()} is {@code true}) the session is
 * marked with the cross-tenant sentinel {@code '0'} — the same pattern established by V4 for
 * {@code core.event_outbox} and extended to {@code connector.*} by V22. Skipping the GUC
 * entirely left the predicate NULL and made every system read return zero rows on an
 * RLS-enforced hub (finding lm-connector-run-rls-404). When no tenant is set on the current
 * thread (and system mode is off), the connection is left unconfigured (no GUC).
 *
 * <p>Quarkus discovers this bean automatically (default datasource = {@code @Default}
 * qualifier) via {@code agroalPoolInterceptors.select(qualifier(dataSourceName))}.
 */
@ApplicationScoped
public class TenantConnectionInterceptor implements AgroalPoolInterceptor {

    private static final Logger LOG = Logger.getLogger(TenantConnectionInterceptor.class);

    @Override
    public void onConnectionAcquire(Connection connection) {
        if (TenantContext.isSystemMode()) {
            // System mode is REAL: mark the session with the cross-tenant sentinel '0' (the V4
            // pattern, extended to connector.* by V22) instead of skipping the GUC. Skipping left
            // the predicate NULL and made every system read return zero rows on an enforced hub
            // (finding lm-connector-run-rls-404).
            try (Statement st = connection.createStatement()) {
                st.execute("SET LOCAL app.current_tenant = '0'");
                LOG.trace("SET LOCAL app.current_tenant = '0' (system mode)");
            } catch (SQLException e) {
                LOG.error("Failed to set system-mode tenant GUC", e);
                throw new RuntimeException("Tenant GUC setup failed (system mode)", e);
            }
            return;
        }

        Long tenantId = CurrentTenantHolder.get();
        if (tenantId == null) return;

        if (tenantId <= 0) {
            // Fail closed: '0' is the system sentinel (V22) — a REQUEST claiming tenant 0 must not
            // be elevated to cross-tenant access. No GUC → NULLIF guard → zero rows.
            LOG.warnf("Rejected non-positive tenant id %d on the tenant path (sentinel spoofing guard)", tenantId);
            return;
        }

        try (Statement st = connection.createStatement()) {
            st.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
            LOG.tracef("SET LOCAL app.current_tenant = %d", tenantId);
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to SET LOCAL app.current_tenant=%d", tenantId);
            throw new RuntimeException("Tenant GUC setup failed", e);
        }
    }
}
