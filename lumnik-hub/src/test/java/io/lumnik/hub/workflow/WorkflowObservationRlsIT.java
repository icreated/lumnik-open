package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V33's row-level security, actually enforced. The store talks to the admin datasource (RLS is
 * bound in SQL there), so only the non-owner {@code lumnik_app} role of this harness can prove
 * the POLICY itself — not any application-level WHERE clause — keeps one tenant's observations
 * and transitions out of another's reach (mirrors WorkflowRlsIT's harness for V32).
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WorkflowObservationRlsIT {

    private static final long TENANT_A = 7501L;
    private static final long TENANT_B = 7502L;
    private static final String WF = "wf-obs-rls-it";

    @Inject WorkflowObservationStore store;

    @Inject AgroalDataSource appDs;   // default DS = lumnik_app, RLS enforced

    /** Count inside a tenant GUC transaction — the same idiom as WorkflowRlsIT.manifestsVisibleTo —
     *  with NO tenant_id WHERE clause: isolation must come only from the RLS policy. The
     *  {@code workflow_name} filter is the WorkflowRlsIT precedent (`WHERE name = 'wf-rls-it'`) and
     *  is load-bearing: WorkflowDormancySweepIT already writes observations for tenant 7501 on
     *  these very tables, so the exact counts below hold only for THIS fixture's rows. */
    private long countVisibleTo(long tenant, String table) {
        try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("BEGIN");
            st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM " + table + " WHERE workflow_name = '" + WF + "'")) {
                rs.next();
                long n = rs.getLong(1);
                st.execute("COMMIT");
                return n;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void the_rls_policy_hides_observations_and_transitions_from_the_other_tenant() {
        Instant now = Instant.now();
        store.insertNew(TENANT_A, WF, "CMD-RLS-1", "EN_COURS", now);
        store.recordTransition(TENANT_A, WF, "CMD-RLS-1", "EN_COURS", "TERMINE", now.plusSeconds(60));

        assertThat(countVisibleTo(TENANT_B, "core.workflow_observation"))
            .as("tenant B must see no observations").isZero();
        assertThat(countVisibleTo(TENANT_B, "core.workflow_transition"))
            .as("tenant B must see no transitions").isZero();

        assertThat(countVisibleTo(TENANT_A, "core.workflow_observation"))
            .as("tenant A sees its own observation").isEqualTo(1L);
        assertThat(countVisibleTo(TENANT_A, "core.workflow_transition"))
            .as("tenant A sees its own transition").isEqualTo(1L);
    }
}
