package io.lumnik.hub.workflow;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #1448 on a real fused view, under enforced RLS: the view is security_invoker and carries no
 * tenant_id, so the only thing between two tenants' order lines is the sweep reading under the tenant.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WorkflowDueSweepIT {

    @Inject WorkflowDueSweep sweep;
    @Inject WorkflowManifestStore manifests;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static final String WF = "wf-due-it";
    private static final String TABLE = "connector.t_dueit_lines";

    private static final String ENTITY = """
        kind: Entity
        name: DueItLine
        match: [line]
        scopes: [dueit]
        sources:
          - table: t_dueit_lines
            map: { line: line, stage: stage, confirmed: confirmed }
        """;

    private static String workflow(String zone) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: wf-due-it }
            spec:
              scope: dueit
              provenance: declared
              on: { table: connector.v_dueitline, column: stage, key: line }
              due: { column: confirmed, states: [CONFIRMED]%s }
              states:
                - { code: ORDERED, initial: true }
                - { code: CONFIRMED, aliases: [confirmée] }
                - { code: DELIVERED, terminal: true }
              transitions:
                - { from: [ORDERED], to: CONFIRMED }
                - { from: [CONFIRMED], to: DELIVERED }
            """.formatted(zone == null ? "" : ", zone: " + zone);
    }

    /** The sweep's clock, fixed: a run that crosses midnight UTC must not move "today" under the assertions. */
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP VIEW IF EXISTS connector.v_dueitline");
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE + " (tenant_id bigint NOT NULL, line text, stage text, confirmed date)");
            st.execute("INSERT INTO " + TABLE + " VALUES "
                + "(1, 'L-1', 'CONFIRMED', '" + TODAY.minusDays(3) + "'),"   // overdue
                + "(1, 'L-2', ' Confirmée', '" + TODAY.minusDays(1) + "'),"  // overdue, through its alias
                + "(1, 'L-3', 'CONFIRMED', '" + TODAY.plusDays(2) + "'),"    // not yet due
                + "(1, 'L-4', 'DELIVERED', '" + TODAY.minusDays(5) + "'),"   // terminal: never late
                + "(1, 'L-5', 'ORDERED', '" + TODAY.minusDays(5) + "'),"     // not a due state
                + "(1, 'L-6', 'CONFIRMED', NULL),"                          // no date, no deadline
                + "(2, 'L-9', 'CONFIRMED', '" + TODAY.minusDays(3) + "')");  // the other tenant's
            st.execute("ALTER TABLE " + TABLE + " ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE " + TABLE + " FORCE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY tenant_isolation ON " + TABLE
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON " + TABLE + " TO lumnik_app");
            st.execute("DELETE FROM core.workflow_due_alert WHERE workflow_name = '" + WF + "'");
            st.execute("DELETE FROM core.event_outbox WHERE event_type = 'decision.overdue." + WF + "'");
            st.execute("DELETE FROM core.workflow_manifest WHERE name = '" + WF + "'");
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply").then().statusCode(200);
        declare(null);
    }

    private void declare(String zone) {
        String yaml = workflow(zone);
        WorkflowManifest m = new WorkflowManifestParser().parse(yaml);
        manifests.upsert(1L, m, yaml, null);
        manifests.upsert(2L, m, yaml, null);
    }

    private List<String> overdue(long tenant) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT entity_key FROM core.event_outbox WHERE tenant_id = ? AND event_type = ? ORDER BY id")) {
            ps.setLong(1, tenant);
            ps.setString(2, "decision.overdue." + WF);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    private void admin(String sql) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** A write to the source after FORCE ROW LEVEL SECURITY: it reaches only the tenant it is made as. */
    private void asTenant(long tenant, String sql) throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
                assertThat(st.executeUpdate(sql)).as(sql).isPositive();
            }
            c.commit();
        }
    }

    @Test
    void each_tenant_hears_of_its_own_overdue_lines_and_only_once_per_deadline() throws Exception {
        sweep.sweepAt(NOW);
        assertThat(overdue(1)).containsExactlyInAnyOrder("L-1", "L-2");
        assertThat(overdue(2)).containsExactly("L-9");

        sweep.sweepAt(NOW);
        assertThat(overdue(1)).as("the same deadline twice is one alert").hasSize(2);

        // The confirmed date moves to another past day: a new deadline, a new alert…
        asTenant(1, "UPDATE " + TABLE + " SET confirmed = '" + TODAY.minusDays(2) + "' WHERE line = 'L-1'");
        sweep.sweepAt(NOW);
        assertThat(overdue(1)).containsExactlyInAnyOrder("L-1", "L-2", "L-1");
        // …and moving back to the deadline already alerted is not news.
        asTenant(1, "UPDATE " + TABLE + " SET confirmed = '" + TODAY.minusDays(3) + "' WHERE line = 'L-1'");
        sweep.sweepAt(NOW);
        assertThat(overdue(1)).hasSize(3);

        // The outbox is purged after its retention; the ledger is what remembers.
        admin("DELETE FROM core.event_outbox WHERE event_type = 'decision.overdue." + WF + "'");
        sweep.sweepAt(NOW);
        assertThat(overdue(1)).isEmpty();
    }

    @Test
    void the_alert_says_the_line_its_date_and_its_state() throws Exception {
        sweep.sweepAt(NOW);
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT source, payload->>'message', payload->>'state', payload->>'due' FROM core.event_outbox "
                    + "WHERE tenant_id = 1 AND event_type = ? AND entity_key = 'L-2'")) {
            ps.setString(1, "decision.overdue." + WF);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("workflow:" + WF);
                assertThat(rs.getString(2)).isEqualTo("L-2: due " + TODAY.minusDays(1)
                    + ", still in \"CONFIRMED\" — declared due date in " + WF);
                assertThat(rs.getString(3)).isEqualTo("CONFIRMED");
                assertThat(rs.getString(4)).isEqualTo(TODAY.minusDays(1).toString());
            }
        }
    }

    /** 22:30 UTC on the 20th is already the 21st in Paris: the 20th is past there, not in UTC. */
    @Test
    void a_date_is_due_at_the_end_of_its_day_in_the_declared_zone() throws Exception {
        asTenant(1, "UPDATE " + TABLE + " SET confirmed = '2026-10-20' WHERE line IN ('L-1', 'L-2')");
        Instant lateEvening = Instant.parse("2026-10-20T22:30:00Z");

        sweep.sweepAt(lateEvening);
        assertThat(overdue(1)).as("UTC: the 20th has not ended").isEmpty();

        declare("Europe/Paris");
        sweep.sweepAt(lateEvening);
        assertThat(overdue(1)).containsExactlyInAnyOrder("L-1", "L-2");
    }

    private static final String TS_TABLE = "connector.t_dueit_stamps";

    private static String stampWorkflow(String column, String zone) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: wf-due-stamps-it }
            spec:
              scope: dueit
              provenance: declared
              on: { table: connector.t_dueit_stamps, column: stage, key: line }
              due: { column: %s, states: [CONFIRMED]%s }
              states:
                - { code: ORDERED, initial: true }
                - { code: CONFIRMED }
                - { code: DELIVERED, terminal: true }
              transitions:
                - { from: [ORDERED], to: CONFIRMED }
                - { from: [CONFIRMED], to: DELIVERED }
            """.formatted(column, zone == null ? "" : ", zone: " + zone);
    }

    private List<String> stampAlerts() throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT entity_key FROM core.event_outbox WHERE tenant_id = 1 AND event_type = ? ORDER BY id")) {
            ps.setString(1, "decision.overdue.wf-due-stamps-it");
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    /**
     * The two timestamp kinds, on an ingest table that keeps a document's versions: a timestamptz is due at
     * its instant, a timestamp without zone at its wall-clock time in spec.due.zone, and only the latest
     * version of a document counts — L-3 was confirmed late once, then delivered.
     */
    @Test
    void a_timestamp_is_due_at_its_instant_or_its_wall_clock_and_only_the_latest_version_counts() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TS_TABLE);
            st.execute("CREATE TABLE " + TS_TABLE + " (id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, line text,"
                + " stage text, due_tz timestamptz, due_local timestamp, _ingested_at timestamptz NOT NULL DEFAULT now())");
            st.execute("INSERT INTO " + TS_TABLE + " (tenant_id, line, stage, due_tz, due_local) VALUES "
                + "(1, 'L-1', 'CONFIRMED', '2026-10-20T11:59:00Z', '2026-10-20T13:59:00'),"  // past, in UTC and in Paris
                + "(1, 'L-2', 'CONFIRMED', '2026-10-20T12:01:00Z', '2026-10-20T14:01:00'),"  // a minute ahead, in both
                + "(1, 'L-3', 'CONFIRMED', '2026-10-19T00:00:00Z', '2026-10-19T00:00:00'),"  // late once…
                + "(1, 'L-3', 'DELIVERED', '2026-10-19T00:00:00Z', '2026-10-19T00:00:00')"); // …then delivered
            st.execute("DELETE FROM core.workflow_due_alert WHERE workflow_name = 'wf-due-stamps-it'");
            st.execute("DELETE FROM core.event_outbox WHERE event_type = 'decision.overdue.wf-due-stamps-it'");
            st.execute("DELETE FROM core.workflow_manifest WHERE name = 'wf-due-stamps-it'");
            st.execute("ALTER TABLE " + TS_TABLE + " ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE " + TS_TABLE + " FORCE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY tenant_isolation ON " + TS_TABLE
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON " + TS_TABLE + " TO lumnik_app");
        }
        String tz = stampWorkflow("due_tz", null);
        manifests.upsert(1L, new WorkflowManifestParser().parse(tz), tz, null);
        sweep.sweepAt(NOW);
        sweep.sweepAt(NOW);
        assertThat(stampAlerts()).as("timestamptz: due at its instant, once").containsExactly("L-1");

        // 13:59 in Paris is 11:59 UTC: past at noon UTC; 14:01 is a minute ahead.
        String local = stampWorkflow("due_local", "Europe/Paris");
        manifests.upsert(1L, new WorkflowManifestParser().parse(local), local, null);
        sweep.sweepAt(NOW);
        sweep.sweepAt(NOW);
        assertThat(stampAlerts()).as("the same instant read from the wall clock is the same deadline")
            .containsExactly("L-1");
        sweep.sweepAt(NOW.plusSeconds(120));
        assertThat(stampAlerts()).containsExactly("L-1", "L-2");
    }
}
