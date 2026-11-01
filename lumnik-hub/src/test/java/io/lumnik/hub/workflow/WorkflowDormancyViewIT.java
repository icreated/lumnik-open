package io.lumnik.hub.workflow;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.LogProbe;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #1588: dormancy over a view with one row per event, ordered by the event's own time, under enforced RLS. */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WorkflowDormancyViewIT {

    @Inject WorkflowDormancySweep sweep;
    @Inject WorkflowManifestStore manifests;
    @Inject WorkflowObservationStore observations;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject ObjectMapper json;

    private static final String WF = "wf-view-it";
    private static final String EVENTS = "connector.t_viewit_events";
    private static final String REGISTRY = "connector.t_viewit_registry";
    /** The freezer's shape: one row per event, a lookup join to the registry that says where and who. */
    private static final String ENTITY = """
        kind: Entity
        name: ViewItEvent
        match: [event_id]
        scopes: [viewit]
        sources:
          - table: t_viewit_events
            map: { event_id: event_id, door: door, door_state: door_state, occurred_at: occurred_at }
        joins:
          - table: t_viewit_registry
            from: door
            to: door
            map: { laboratory: laboratory, owner: owner, owner_email: owner_email }
        """;
    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-view-it }
        spec:
          scope: viewit
          provenance: declared
          on: { table: connector.v_viewitevent, column: door_state, key: door, order: occurred_at }
          dormancy:
            after: 1m
            message: "{door} ({laboratory}): door {state} since {since}. Owner: {owner}"
            fields: [owner_email]
          states:
            - { code: OPEN, initial: true, aliases: [ouverte] }
            - { code: CLOSED, terminal: true }
          transitions:
            - { from: [OPEN], to: CLOSED }
        """;

    private void sql(String s) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(s);
        }
    }

    /** A write after FORCE ROW LEVEL SECURITY: it reaches only the tenant it is made as, and must reach a row. */
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

    private void event(long tenant, String id, String door, String state, String at) throws Exception {
        asTenant(tenant, "INSERT INTO " + EVENTS + " (tenant_id, event_id, door, door_state, occurred_at) VALUES ("
            + tenant + ", '" + id + "', '" + door + "', '" + state + "', '" + at + "')");
    }

    private void declare(long tenant, String yaml) {
        manifests.upsert(tenant, new WorkflowManifestParser().parse(yaml), yaml, null);
    }

    private void rlsTable(String table, String columns) throws Exception {
        sql("DROP TABLE IF EXISTS " + table);
        sql("CREATE TABLE " + table + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, " + columns
            + ", _ingested_at timestamptz NOT NULL DEFAULT now())");
        sql("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
        sql("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
        sql("CREATE POLICY tenant_isolation ON " + table
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        sql("GRANT SELECT ON " + table + " TO lumnik_app");
    }

    private JsonNode onlyDormantPayload(long tenant) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT payload::text FROM core.event_outbox WHERE tenant_id = ? AND event_type = ? ORDER BY id")) {
            ps.setLong(1, tenant);
            ps.setString(2, "decision.dormant." + WF);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("one dormancy decision").isTrue();
                JsonNode p = json.readTree(rs.getString(1));
                assertThat(rs.next()).as("only one").isFalse();
                return p;
            }
        }
    }

    private void age() throws Exception {
        sql("UPDATE core.workflow_observation SET since = NOW() - INTERVAL '2 minutes' WHERE workflow_name = '" + WF + "'");
    }

    @BeforeEach
    void seed() throws Exception {
        sql("DROP VIEW IF EXISTS connector.v_viewitevent");
        rlsTable(REGISTRY, "door text, laboratory text, owner text, owner_email text");
        asTenant(1, "INSERT INTO " + REGISTRY + " (tenant_id, door, laboratory, owner, owner_email) VALUES "
            + "(1, 'D1', 'Cryobiology Lab', 'Dr. Lina Moreau', 'l.moreau@example.org')");
        sql("DELETE FROM core.event_outbox WHERE event_type = 'decision.dormant." + WF + "'");
        sql("DROP TABLE IF EXISTS " + EVENTS);
        sql("CREATE TABLE " + EVENTS + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, event_id text, "
            + "door text, door_state text, occurred_at text, _ingested_at timestamptz NOT NULL DEFAULT now())");
        sql("ALTER TABLE " + EVENTS + " ENABLE ROW LEVEL SECURITY");
        sql("ALTER TABLE " + EVENTS + " FORCE ROW LEVEL SECURITY");
        sql("CREATE POLICY tenant_isolation ON " + EVENTS
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        sql("GRANT SELECT ON " + EVENTS + " TO lumnik_app");
        sql("DELETE FROM core.workflow_observation WHERE workflow_name = '" + WF + "'");
        sql("DELETE FROM core.workflow_manifest WHERE name = '" + WF + "'");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY).post("/api/entities/apply").then().statusCode(200);
        declare(1L, YAML);
        declare(2L, YAML);
    }

    @Test
    void the_latest_event_by_its_own_time_is_the_current_state() throws Exception {
        event(1, "E1", "D1", "CLOSED", "2026-10-26T10:05:00Z");
        event(1, "E2", "D1", "OPEN", "2026-10-26T10:00:00Z");
        event(1, "E3", "D2", "OPEN", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        assertThat(observations.loadAll(1L, WF).get("D1").state()).isEqualTo("CLOSED");
        assertThat(observations.loadAll(1L, WF).get("D2").state()).isEqualTo("OPEN");
        assertThat(observations.loadAll(2L, WF)).as("tenant 2 sees none of tenant 1's doors").isEmpty();
    }

    @Test
    void a_tie_with_different_states_is_ambiguous_and_suspends_the_observation() throws Exception {
        event(1, "E1", "D3", "OPEN", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        assertThat(observations.loadAll(1L, WF)).containsKey("D3");
        sql("UPDATE core.workflow_observation SET since = NOW() - INTERVAL '2 hours' WHERE workflow_name = '" + WF + "'");
        event(1, "E2", "D3", "CLOSED", "2026-10-26T11:00:00+01:00");   // the same instant
        Instant suspended = Instant.now();
        sweep.sweepNow();
        assertThat(observations.loadAll(1L, WF).get("D3").since()).as("suspended: its clock restarts")
            .isAfterOrEqualTo(suspended.minusSeconds(1));
    }

    @Test
    void a_tie_on_two_spellings_of_one_state_is_not_ambiguous() throws Exception {
        event(1, "E1", "D4", "OPEN", "2026-10-26T10:00:00Z");
        event(1, "E2", "D4", "ouverte", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        assertThat(observations.loadAll(1L, WF).get("D4").state()).isEqualTo("OPEN");
    }

    @Test
    void an_invalid_order_value_makes_the_key_unreadable() throws Exception {
        event(1, "E1", "D6", "OPEN", "2026-10-26T10:00:00Z");
        event(1, "E2", "D6", "CLOSED", "2026-10-26T10:05:00");   // no offset
        sweep.sweepNow();
        assertThat(observations.loadAll(1L, WF)).as("never observed: unreadable from the start")
            .doesNotContainKey("D6");
    }

    @Test
    void pages_never_split_a_key_and_visit_every_key_once() throws Exception {
        sweep.pageRows(2);
        try {
            event(1, "E1", "a", "OPEN", "2026-10-26T10:00:00Z");
            event(1, "E2", "B", "OPEN", "2026-10-26T10:00:00Z");
            event(1, "E3", "B", "CLOSED", "2026-10-26T10:01:00Z");
            event(1, "E4", "B", "OPEN", "2026-10-26T10:02:00Z");
            event(1, "E5", "c", "CLOSED", "2026-10-26T10:00:00Z");
            sweep.sweepNow();
            var obs = observations.loadAll(1L, WF);
            assertThat(obs).containsOnlyKeys("a", "B", "c");
            assertThat(obs.get("B").state()).isEqualTo("OPEN");
        } finally {
            sweep.pageRows(1_000);
        }
    }

    @Test
    void moving_from_the_table_to_the_view_keeps_the_observation() throws Exception {
        event(1, "E1", "D5", "OPEN", "2026-10-26T10:00:00Z");
        // A table is read on the admin datasource, which is BYPASSRLS in production. This profile's admin owns the
        // table but does not bypass, and FORCE would hide every row from it: lift FORCE for the table phase.
        sql("ALTER TABLE " + EVENTS + " NO FORCE ROW LEVEL SECURITY");
        declare(1L, YAML.replace("connector.v_viewitevent", EVENTS));
        sweep.sweepNow();
        var before = observations.loadAll(1L, WF).get("D5");
        declare(1L, YAML);
        sweep.sweepNow();
        assertThat(observations.loadAll(1L, WF).get("D5").since()).isEqualTo(before.since());
    }

    /** #1588: the joined registry reaches the decision: where the door is, and who must act. */
    @Test
    void the_decision_names_the_laboratory_and_the_owner() throws Exception {
        event(1, "E1", "D1", "OPEN", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        age();
        sweep.sweepNow();
        JsonNode p = onlyDormantPayload(1L);
        assertThat(p.get("message").asText()).startsWith("D1 (Cryobiology Lab): door OPEN since ")
            .endsWith(". Owner: Dr. Lina Moreau");
        assertThat(p.at("/fields/door").asText()).isEqualTo("D1");
        assertThat(p.at("/fields/laboratory").asText()).isEqualTo("Cryobiology Lab");
        assertThat(p.at("/fields/owner").asText()).isEqualTo("Dr. Lina Moreau");
        assertThat(p.at("/fields/owner_email").asText()).isEqualTo("l.moreau@example.org");
        assertThat(p.has("process")).isFalse();
    }

    /** State and context come from the version that was read: a newer row landing before the emission changes nothing. */
    @Test
    void the_decision_carries_the_version_that_was_read_not_a_newer_one() throws Exception {
        event(1, "E1", "D1", "OPEN", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        age();
        AtomicBoolean landed = new AtomicBoolean();
        sweep.afterRead(() -> {
            if (!landed.compareAndSet(false, true)) return;
            try {
                event(1, "E9", "D1", "CLOSED", "2026-10-26T10:09:00Z");
                asTenant(1, "UPDATE " + REGISTRY + " SET owner = 'Someone Else'");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            sweep.sweepNow();
        } finally {
            sweep.afterRead(() -> {});
        }
        JsonNode p = onlyDormantPayload(1L);
        assertThat(p.get("state").asText()).isEqualTo("OPEN");
        assertThat(p.at("/fields/owner").asText()).isEqualTo("Dr. Lina Moreau");
    }

    /** Tied rows that agree are one version: the first by identity is the one read, and its columns are the decision's. */
    @Test
    void tied_rows_that_agree_carry_the_columns_of_the_smallest_identity() throws Exception {
        declare(1L, YAML.replace("fields: [owner_email]", "fields: [event_id]"));
        event(1, "E2", "D1", "OPEN", "2026-10-26T10:00:00Z");              // ingested first
        event(1, "E1", "D1", "ouverte", "2026-10-26T11:00:00+01:00");      // the same instant, the same state
        sweep.sweepNow();
        age();
        sweep.sweepNow();
        assertThat(onlyDormantPayload(1L).at("/fields/event_id").asText()).isEqualTo("E1");
    }

    /** The alarm matters more than the sentence: a cited column gone renders empty, and the alert still fires. */
    @Test
    void a_cited_column_dropped_after_apply_renders_empty_and_the_alert_still_fires() throws Exception {
        event(1, "E1", "D1", "OPEN", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
            .body(ENTITY.replace(", owner: owner", "")).post("/api/entities/apply").then().statusCode(200);
        age();
        List<String> warnings = LogProbe.warnings(WorkflowDormancySweep.class, sweep::sweepNow);
        JsonNode p = onlyDormantPayload(1L);
        assertThat(p.get("message").asText()).endsWith(". Owner: ");
        assertThat(p.at("/fields/owner").isNull()).isTrue();
        assertThat(warnings).anyMatch(w -> w.contains("cites owner"));
    }
}
