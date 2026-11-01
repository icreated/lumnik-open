package io.lumnik.hub.workflow;

import static io.restassured.RestAssured.given;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Manual benchmark for #1595 — one dormancy sweep over a fused view of {@code bench.keys} documents (100,000 by
 * default), two events each, under the production posture (readonly transactional, RLS forced). Named without a
 * {@code Test}/{@code IT} suffix so no gate picks it up; it reports numbers rather than asserting a budget. Run:
 *
 * <pre>mvn verify -pl lumnik-hub -Dtest=ViewSweepBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dbench.keys=100000</pre>
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class ViewSweepBenchmark {

    @Inject WorkflowDormancySweep sweep;
    @Inject WorkflowManifestStore manifests;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static final String EVENTS = "connector.t_viewbench_events";
    private static final String ENTITY = """
        kind: Entity
        name: ViewBenchEvent
        match: [event_id]
        scopes: [viewbench]
        sources:
          - table: t_viewbench_events
            map: { event_id: event_id, door: door, door_state: door_state, occurred_at: occurred_at }
        """;
    private static final String WORKFLOW = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-view-bench }
        spec:
          scope: viewbench
          provenance: declared
          on: { table: connector.v_viewbenchevent, column: door_state, key: door, order: occurred_at }
          dormancy: { after: 30d }
          states:
            - { code: OPEN, initial: true }
            - { code: CLOSED, terminal: true }
          transitions:
            - { from: [OPEN], to: CLOSED }
        """;

    private void sql(String... statements) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    @Test
    void one_sweep_over_a_large_view() throws Exception {
        int n = Integer.getInteger("bench.keys", 100_000);
        sql("DROP VIEW IF EXISTS connector.v_viewbenchevent",
            "DROP TABLE IF EXISTS " + EVENTS,
            "CREATE TABLE " + EVENTS + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, event_id text, "
                + "door text, door_state text, occurred_at text, _ingested_at timestamptz NOT NULL DEFAULT now())",
            "INSERT INTO " + EVENTS + " (tenant_id, event_id, door, door_state, occurred_at) "
                + "SELECT 1, 'E' || i || '-' || j, 'D' || i, CASE WHEN j = 1 THEN 'OPEN' ELSE 'CLOSED' END, "
                + "'2026-10-26T10:0' || j || ':00Z' FROM generate_series(1, " + n + ") i, generate_series(1, 2) j",
            "ANALYZE " + EVENTS,
            "ALTER TABLE " + EVENTS + " ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE " + EVENTS + " FORCE ROW LEVEL SECURITY",
            "CREATE POLICY tenant_isolation ON " + EVENTS
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)",
            "GRANT SELECT ON " + EVENTS + " TO lumnik_app",
            "DELETE FROM core.workflow_observation WHERE workflow_name = 'wf-view-bench'",
            "DELETE FROM core.workflow_manifest WHERE name = 'wf-view-bench'");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY).post("/api/entities/apply").then().statusCode(200);
        manifests.upsert(1L, new WorkflowManifestParser().parse(WORKFLOW), WORKFLOW, null);

        for (int run = 1; run <= 2; run++) {
            long t0 = System.nanoTime();
            sweep.sweepNow();
            System.out.printf("BENCH keys=%d sweep#%d %d ms%n", n, run, (System.nanoTime() - t0) / 1_000_000);
        }
    }
}
