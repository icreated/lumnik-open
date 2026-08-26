package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-tenant data-isolation proof on a kept HUB table, under a real (non-bypass) RLS-enforcing
 * role.
 *
 * <p>This restores the guarantee the deleted {@code TenantIsolationIT} used to prove (it lived on
 * the now-archived business-partner domain — Chantier B Phase 1). The tenant-isolation
 * <em>mechanism</em> survived the domain deletion; this test re-establishes the cross-tenant
 * end-to-end <em>proof</em> on a HUB entity that is here to stay.
 *
 * <p><b>The superuser blind spot it closes.</b> In the {@code @QuarkusTest} DevServices DB the
 * connecting user is the auto-provisioned <em>superuser</em> that owns the schema, and a superuser
 * bypasses RLS unconditionally — even {@code FORCE ROW LEVEL SECURITY} cannot subject it. A test
 * run as that user would prove nothing. So, mirroring {@link RlsEmptyTenantGucIT}, we create a
 * throwaway {@code NOLOGIN NOBYPASSRLS} probe role, {@code FORCE} RLS on the target table, and
 * {@code SET ROLE} to that role on the query connection. Only then does the {@code tenant_isolation}
 * policy actually filter.
 *
 * <p><b>Target table.</b> {@code connector.connector} — a tenant-scoped HUB table carrying the
 * {@code tenant_isolation} policy {@code USING (tenant_id = NULLIF(current_setting(
 * 'app.current_tenant', true), '')::bigint)} (post-A2 V21). Chosen over {@code connector_run}
 * because it has no inbound FK requirement, so it seeds cleanly with raw admin SQL: only
 * {@code tenant_id}, {@code name}, {@code type_id} are NOT-NULL without a default.
 */
@QuarkusTest
class TenantHubIsolationIT {

    private static final String PROBE_ROLE = "tenant_hub_isolation_probe_role";
    private static final String NAME_PREFIX = "tenant-hub-isolation-probe-";

    private static final long TENANT_1 = 1L;
    private static final long TENANT_2 = 2L;

    /** Ids of the rows we seed, so we can assert visibility/invisibility precisely. */
    private UUID tenant1RowId;
    private UUID tenant2RowId;

    /** The DEFAULT datasource — we SET ROLE to the probe role on it so RLS actually applies. */
    @Inject
    AgroalDataSource defaultDs;

    /** Admin datasource (BYPASSRLS) — used to provision the role, FORCE RLS, and seed rows. */
    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void provisionRoleAndSeed() throws Exception {
        tenant1RowId = UUID.randomUUID();
        tenant2RowId = UUID.randomUUID();

        try (Connection c = adminDs.getConnection()) {
            try (Statement st = c.createStatement()) {
                // A plain role (no superuser, no BYPASSRLS) so RLS policies apply when we SET ROLE to it.
                st.execute("DROP ROLE IF EXISTS " + PROBE_ROLE);
                st.execute("CREATE ROLE " + PROBE_ROLE + " NOLOGIN NOBYPASSRLS");
                st.execute("GRANT USAGE ON SCHEMA connector TO " + PROBE_ROLE);
                st.execute("GRANT SELECT ON connector.connector TO " + PROBE_ROLE);
                // Make the policy apply even to the table owner / role-switched session.
                st.execute("ALTER TABLE connector.connector FORCE ROW LEVEL SECURITY");
            }
            // Seed one row for each tenant. Only tenant_id, name, type_id are NOT-NULL w/o default.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO connector.connector (id, tenant_id, name, type_id) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, tenant1RowId);
                ps.setLong(2, TENANT_1);
                ps.setString(3, NAME_PREFIX + "t1");
                ps.setString(4, "tenant-hub-isolation-probe");
                ps.executeUpdate();

                ps.setObject(1, tenant2RowId);
                ps.setLong(2, TENANT_2);
                ps.setString(3, NAME_PREFIX + "t2");
                ps.setString(4, "tenant-hub-isolation-probe");
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM connector.connector WHERE name LIKE '" + NAME_PREFIX + "%'");
            st.execute("ALTER TABLE connector.connector NO FORCE ROW LEVEL SECURITY");
            st.execute("REVOKE ALL ON connector.connector FROM " + PROBE_ROLE);
            st.execute("REVOKE USAGE ON SCHEMA connector FROM " + PROBE_ROLE);
            st.execute("DROP ROLE IF EXISTS " + PROBE_ROLE);
        }
    }

    @Test
    void hubTable_isolatesRowsAcrossTenants_underNonBypassRole() throws Exception {
        // ── Non-vacuity guard ────────────────────────────────────────────────────────────────
        // Via the admin (BYPASSRLS) datasource, both tenants' rows MUST physically exist. Without
        // this, the RLS-scoped invisibility asserted below could be a false green from an empty
        // table rather than real per-tenant filtering.
        try (Connection admin = adminDs.getConnection()) {
            assertThat(countByName(admin, NAME_PREFIX + "t1"))
                .as("non-vacuity: tenant-1 row must physically exist (visible to BYPASSRLS admin)")
                .isEqualTo(1L);
            assertThat(countByName(admin, NAME_PREFIX + "t2"))
                .as("non-vacuity: tenant-2 row must physically exist (visible to BYPASSRLS admin)")
                .isEqualTo(1L);
        }

        // ── Isolation under the probe role ───────────────────────────────────────────────────
        // CRITICAL — leak-free session state. We must NOT leave SET ROLE / app.current_tenant on
        // the pooled `default` connection: a later test reusing it (e.g. TenantInterceptorIT, which
        // asserts an EMPTY GUC in system mode) would see our values and fail. So every session
        // setting is issued as tx-scoped SET LOCAL inside an explicit transaction that we always
        // roll back — SET LOCAL is auto-discarded at transaction end, so nothing ever persists on
        // the connection returned to the pool.
        try (Connection c = defaultDs.getConnection()) {
            // Tenant 1 sees ONLY its own probe row; tenant-2's row is invisible (no SQL filter —
            // RLS does all the filtering).
            runScopedAsTenant(c, TENANT_1, () -> {
                assertThat(countByName(c, NAME_PREFIX + "t1"))
                    .as("tenant=1: its own seeded row is visible")
                    .isEqualTo(1L);
                assertThat(countById(c, tenant2RowId))
                    .as("tenant=1: tenant-2's row must be INVISIBLE (RLS isolation)")
                    .isZero();
            });

            // Tenant 2 sees ONLY its own probe row; tenant-1's row is invisible.
            runScopedAsTenant(c, TENANT_2, () -> {
                assertThat(countByName(c, NAME_PREFIX + "t2"))
                    .as("tenant=2: its own seeded row is visible")
                    .isEqualTo(1L);
                assertThat(countById(c, tenant1RowId))
                    .as("tenant=2: tenant-1's row must be INVISIBLE (RLS isolation)")
                    .isZero();
            });
        }
    }

    /**
     * Runs {@code probe} on {@code c} inside a single explicit transaction, having entered the
     * non-bypass probe role and pinned the tenant GUC — both via tx-scoped {@code SET LOCAL}. The
     * transaction is ALWAYS rolled back, so neither the role nor the tenant GUC survives onto the
     * pooled connection (no cross-test leakage). {@code SET LOCAL ROLE} is valid inside a tx and is
     * reverted automatically at tx end, exactly like a plain {@code SET LOCAL}.
     */
    private static void runScopedAsTenant(Connection c, long tenant, ThrowingRunnable probe)
            throws Exception {
        boolean prevAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (Statement st = c.createStatement()) {
                // Become a plain role so RLS policies are actually enforced — tx-scoped.
                st.execute("SET LOCAL ROLE " + PROBE_ROLE);
                st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
            }
            probe.run();
        } finally {
            // Discard the transaction → SET LOCAL ROLE + app.current_tenant vanish with it.
            c.rollback();
            c.setAutoCommit(prevAutoCommit);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static long countByName(Connection c, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM connector.connector WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countById(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM connector.connector WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
