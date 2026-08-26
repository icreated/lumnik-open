package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Targeted reproduction of the RLS empty-tenant-GUC bug ([[project-connector-run-async-fix]]).
 *
 * <p>Every RLS policy casts the tenant GUC directly:
 * {@code tenant_id = current_setting('app.current_tenant', true)::bigint}. If that GUC is ever set
 * to an EMPTY STRING ({@code ''}) — as happened on the live hub during an async connector run —
 * the cast {@code ''::bigint} throws PostgreSQL error {@code 22P02 invalid input syntax for type
 * bigint: ""}. On the live hub this surfaced as a connector run that silently no-ops: the RLS-
 * protected query blew up with 22P02 and the error was swallowed.
 *
 * <p>This test forces the empty-GUC condition directly at the DB layer and asserts the DESIRED
 * post-fix behaviour: the query returns cleanly (an empty/NULL tenant matches no rows → count 0)
 * rather than throwing.
 *
 * <p><b>Today this test is RED</b> — the direct cast throws 22P02. After the defensive migration
 * wraps the cast as {@code NULLIF(current_setting('app.current_tenant', true), '')::bigint}, an
 * empty GUC yields {@code NULL}, the predicate is false, the query returns 0 rows, and this test
 * goes GREEN.
 *
 * <p>We target {@code core.installed_module}, which carries the {@code tenant_isolation} policy
 * from {@code core/V2__rls_policies.sql}.
 *
 * <p><b>Why the harness needs a dedicated role:</b> in production the {@code lumnik_app} login is
 * a plain (non-superuser, non-BYPASSRLS) role, so RLS policies apply to it. In the
 * {@code @QuarkusTest} DevServices DB, the connecting user is the auto-provisioned <em>superuser</em>
 * that owns the schema — and a superuser bypasses RLS unconditionally (even {@code FORCE ROW LEVEL
 * SECURITY} cannot subject it). So the policy expression is never evaluated and the empty-GUC cast
 * never fires under the default connection as-is. To faithfully reproduce the live condition we
 * create a throwaway plain role, {@code FORCE} RLS on the target table, then {@code SET ROLE} to that
 * role on the query connection — now RLS applies and the {@code ::bigint} cast runs per row. We seed
 * one row so a candidate row exists for the predicate to be evaluated against.
 */
@QuarkusTest
class RlsEmptyTenantGucIT {

    private static final String PROBE_ROLE = "rls_empty_guc_probe_role";

    /** The DEFAULT datasource — we SET ROLE to a plain role on it to make RLS apply. */
    @Inject
    AgroalDataSource defaultDs;

    /** Admin datasource — used to provision the probe role, FORCE RLS, and seed a row (setup only). */
    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void provisionRoleAndSeed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            // A plain role (no superuser, no BYPASSRLS) so RLS policies apply when we SET ROLE to it.
            st.execute("DROP ROLE IF EXISTS " + PROBE_ROLE);
            st.execute("CREATE ROLE " + PROBE_ROLE + " NOLOGIN NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA core TO " + PROBE_ROLE);
            st.execute("GRANT SELECT ON core.installed_module TO " + PROBE_ROLE);
            // Make the policy apply even to the table owner / role-switched session.
            st.execute("ALTER TABLE core.installed_module FORCE ROW LEVEL SECURITY");
            // RLS evaluates the USING predicate per candidate row; seed one so the cast fires.
            st.execute("INSERT INTO core.installed_module (tenant_id, module_id, active) "
                + "VALUES (1, 'rls-empty-guc-probe', true) ON CONFLICT DO NOTHING");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM core.installed_module WHERE module_id = 'rls-empty-guc-probe'");
            st.execute("ALTER TABLE core.installed_module NO FORCE ROW LEVEL SECURITY");
            st.execute("REVOKE ALL ON core.installed_module FROM " + PROBE_ROLE);
            st.execute("REVOKE USAGE ON SCHEMA core FROM " + PROBE_ROLE);
            st.execute("DROP ROLE IF EXISTS " + PROBE_ROLE);
        }
    }

    @Test
    void emptyTenantGuc_onRlsProtectedTable_returnsZeroRows_doesNotThrow22P02() throws Exception {
        // CRITICAL — leak-free session state. SET ROLE / app.current_tenant must NOT survive onto
        // the pooled `default` connection (a later test reusing it would inherit our role and GUC).
        // Issue both as tx-scoped SET LOCAL inside an explicit transaction we always roll back, so
        // the session state vanishes with the transaction.
        try (Connection c = defaultDs.getConnection()) {
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    // Become a plain role so RLS policies are actually enforced — tx-scoped.
                    st.execute("SET LOCAL ROLE " + PROBE_ROLE);
                    // Mimic the live empty-GUC condition that the async run hit — tx-scoped.
                    st.execute("SET LOCAL app.current_tenant = ''");
                }

                final long[] count = {-1L};

                assertThatCode(() -> {
                    try (Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT count(*) FROM core.installed_module")) {
                        rs.next();
                        count[0] = rs.getLong(1);
                    }
                })
                .as("an empty tenant GUC must NOT crash the RLS cast with 22P02 "
                    + "(invalid input syntax for type bigint: \"\"); it must degrade to a NULL "
                    + "tenant matching no rows. This is RED today and goes GREEN after the NULLIF guard.")
                .doesNotThrowAnyException();

                assertThat(count[0])
                    .as("an empty/NULL tenant must match no rows under RLS")
                    .isZero();
            } finally {
                // Discard the transaction → SET LOCAL ROLE + app.current_tenant vanish with it.
                c.rollback();
                c.setAutoCommit(prevAutoCommit);
            }
        }
    }
}
