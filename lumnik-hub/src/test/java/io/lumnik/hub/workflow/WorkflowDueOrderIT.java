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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #1588: the due sweep picks a line's current version by spec.on.order, on a table and on a per-event view. */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WorkflowDueOrderIT {

    @Inject WorkflowDueSweep sweep;
    @Inject WorkflowManifestStore manifests;
    @Inject ObjectMapper json;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static final String WF = "wf-due-order-it";
    private static final String EVENTS = "connector.t_dueorder_events";
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
    private static final String ENTITY = """
        kind: Entity
        name: DueOrderEvent
        match: [event_id]
        scopes: [dueorder]
        sources:
          - table: t_dueorder_events
            map: { event_id: event_id, line: line, stage: stage, due_on: due_on, seen: seen }
        """;

    private static String workflow(String table) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: wf-due-order-it }
            spec:
              scope: dueorder
              provenance: declared
              on: { table: %s, column: stage, key: line, order: seen }
              due: { column: due_on, states: [CONFIRMED] }
              states:
                - { code: ORDERED, initial: true }
                - { code: CONFIRMED }
                - { code: DELIVERED, terminal: true }
              transitions:
                - { from: [ORDERED], to: CONFIRMED }
                - { from: [CONFIRMED], to: DELIVERED }
            """.formatted(table);
    }

    private void sql(String s) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(s);
        }
    }

    private void event(String id, String line, String stage, LocalDate due, String seen) throws Exception {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL app.current_tenant = '1'");
                st.execute("INSERT INTO " + EVENTS + " (tenant_id, event_id, line, stage, due_on, seen) VALUES (1, '"
                    + id + "', '" + line + "', '" + stage + "', " + (due == null ? "NULL" : "'" + due + "'") + ", '"
                    + seen + "')");
            }
            c.commit();
        }
    }

    private List<String> overdue() throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT entity_key FROM core.event_outbox WHERE tenant_id = 1 AND event_type = ? ORDER BY id")) {
            ps.setString(1, "decision.overdue." + WF);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    private void declare(String table) {
        String yaml = workflow(table);
        manifests.upsert(1L, new WorkflowManifestParser().parse(yaml), yaml, null);
    }

    @BeforeEach
    void seed() throws Exception {
        sql("DROP VIEW IF EXISTS connector.v_dueorderevent");
        sql("DROP TABLE IF EXISTS " + EVENTS);
        sql("CREATE TABLE " + EVENTS + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, event_id text, "
            + "line text, stage text, due_on date, seen text, _ingested_at timestamptz NOT NULL DEFAULT now())");
        sql("ALTER TABLE " + EVENTS + " ENABLE ROW LEVEL SECURITY");
        sql("ALTER TABLE " + EVENTS + " FORCE ROW LEVEL SECURITY");
        sql("CREATE POLICY tenant_isolation ON " + EVENTS
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        sql("GRANT SELECT ON " + EVENTS + " TO lumnik_app");
        sql("DELETE FROM core.workflow_due_alert WHERE workflow_name = '" + WF + "'");
        sql("DELETE FROM core.event_outbox WHERE event_type = 'decision.overdue." + WF + "'");
        sql("DELETE FROM core.workflow_manifest WHERE name = '" + WF + "'");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY).post("/api/entities/apply").then().statusCode(200);
    }

    /**
     * A column name is the source's, free, and quoted as the only guard: one may contain " FROM ". The statement is
     * built from its parts, never by rewriting its text, so such a name stays a name with or without an order.
     */
    @Test
    void a_column_named_with_from_inside_stays_a_name() throws Exception {
        String odd = "connector.t_dueorder_odd";
        sql("DROP TABLE IF EXISTS " + odd);
        sql("CREATE TABLE " + odd + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, \"line FROM here\" text, "
            + "stage text, due_on date, seen text, _ingested_at timestamptz NOT NULL DEFAULT now())");
        sql("GRANT SELECT ON " + odd + " TO lumnik_app");
        sql("INSERT INTO " + odd + " (tenant_id, \"line FROM here\", stage, due_on, seen) VALUES "
            + "(1, 'L-8', 'CONFIRMED', '" + TODAY.minusDays(2) + "', '2026-10-19T08:00:00Z')");
        for (String order : new String[] {"", ", order: seen"}) {
            sql("DELETE FROM core.workflow_due_alert WHERE workflow_name = '" + WF + "'");
            sql("DELETE FROM core.event_outbox WHERE event_type = 'decision.overdue." + WF + "'");
            String yaml = workflow(odd).replace("key: line, order: seen", "key: \"line FROM here\"" + order);
            manifests.upsert(1L, new WorkflowManifestParser().parse(yaml), yaml, null);
            sweep.sweepAt(NOW);
            assertThat(overdue()).as("order: " + order).containsExactly("L-8");
        }
    }

    /**
     * A cited column dropped between the catalog read and the statement (an entity re-applied in between) is a 42703
     * once: the tick reads the catalog again, the column renders empty, and the overdue line is still raised.
     */
    @Test
    void a_cited_column_dropped_mid_tick_rebuilds_once_and_the_alert_still_fires() throws Exception {
        String table = "connector.t_dueorder_drop";
        sql("DROP TABLE IF EXISTS " + table);
        sql("CREATE TABLE " + table + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, line text, stage text, "
            + "due_on date, seen text, dock text, _ingested_at timestamptz NOT NULL DEFAULT now())");
        sql("GRANT SELECT ON " + table + " TO lumnik_app");
        sql("INSERT INTO " + table + " (tenant_id, line, stage, due_on, seen, dock) VALUES "
            + "(1, 'L-7', 'CONFIRMED', '" + TODAY.minusDays(2) + "', '2026-10-19T08:00:00Z', 'dock 4')");
        String yaml = workflow(table)
            .replace("states: [CONFIRMED] }", "states: [CONFIRMED], message: \"{doc_key}: {dock}\" }");
        manifests.upsert(1L, new WorkflowManifestParser().parse(yaml), yaml, null);
        AtomicBoolean dropped = new AtomicBoolean();
        sweep.beforeRead(() -> {
            if (dropped.compareAndSet(false, true)) {
                try {
                    sql("ALTER TABLE " + table + " DROP COLUMN dock");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        try {
            sweep.sweepAt(NOW);
        } finally {
            sweep.beforeRead(() -> {});
        }
        assertThat(onlyOverduePayload().get("message").asText()).isEqualTo("L-7: ");
    }

    /** A due date may drop its order on a view; what only the order could hold is released, and said again later. */
    @Test
    void an_order_removed_then_restored_says_its_ambiguous_keys_again() throws Exception {
        declare("connector.v_dueorderevent");
        sweep.sweepAt(NOW);   // a clean tick: whatever an earlier test left held is released
        event("E1", "L-9", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
        event("E2", "L-9", "CONFIRMED", TODAY.minusDays(1), "2026-10-19T08:00:00Z");
        assertThat(dueWarnings()).anyMatch(w -> w.contains("its latest versions disagree"));
        String unordered = workflow("connector.v_dueorderevent").replace(", order: seen", "");
        manifests.upsert(1L, new WorkflowManifestParser().parse(unordered), unordered, null);
        sweep.sweepAt(NOW);
        declare("connector.v_dueorderevent");
        assertThat(dueWarnings()).as("said again after the order came back")
            .anyMatch(w -> w.contains("its latest versions disagree"));
    }

    /** A due date removed, then declared again on a column still no date, says its refusal again. */
    @Test
    void a_due_date_removed_then_restored_says_its_refusal_again() throws Exception {
        String onText = workflow(EVENTS).replace("due: { column: due_on", "due: { column: seen");
        String without = onText.replace("  due: { column: seen, states: [CONFIRMED] }\n", "");
        manifests.upsert(1L, new WorkflowManifestParser().parse(onText), onText, null);
        assertThat(dueWarnings()).anyMatch(w -> w.contains("spec.due.column seen is text"));
        manifests.upsert(1L, new WorkflowManifestParser().parse(without), without, null);
        sweep.sweepAt(NOW);
        manifests.upsert(1L, new WorkflowManifestParser().parse(onText), onText, null);
        assertThat(dueWarnings()).as("said again after the due date came back")
            .anyMatch(w -> w.contains("spec.due.column seen is text"));
    }

    private List<String> dueWarnings() {
        return LogProbe.warnings(WorkflowDueSweep.class, () -> sweep.sweepAt(NOW)).stream()
            .filter(w -> w.contains(WF)).toList();
    }

    private JsonNode onlyOverduePayload() throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT payload::text FROM core.event_outbox WHERE tenant_id = 1 AND event_type = ? ORDER BY id")) {
            ps.setString(1, "decision.overdue." + WF);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("one overdue decision").isTrue();
                JsonNode p = json.readTree(rs.getString(1));
                assertThat(rs.next()).as("only one").isFalse();
                return p;
            }
        }
    }

    /** #1588: a due decision cites the columns of the version it is about, on a view and on a table. */
    @Test
    void an_overdue_decision_carries_its_cited_and_declared_columns() throws Exception {
        for (String table : new String[] {"connector.v_dueorderevent", EVENTS}) {
            sql("DELETE FROM core.workflow_due_alert WHERE workflow_name = '" + WF + "'");
            sql("DELETE FROM core.event_outbox WHERE event_type = 'decision.overdue." + WF + "'");
            sql("DELETE FROM " + EVENTS);
            String yaml = workflow(table).replace("states: [CONFIRMED] }",
                "states: [CONFIRMED], message: \"{doc_key} ({event_id}) due {due}\", fields: [seen] }");
            manifests.upsert(1L, new WorkflowManifestParser().parse(yaml), yaml, null);
            event("E5", "L-5", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
            sweep.sweepAt(NOW);
            JsonNode p = onlyOverduePayload();
            assertThat(p.get("message").asText()).as(table).isEqualTo("L-5 (E5) due " + TODAY.minusDays(2));
            assertThat(p.at("/fields/event_id").asText()).as(table).isEqualTo("E5");
            assertThat(p.at("/fields/seen").asText()).as(table).isEqualTo("2026-10-19T08:00:00Z");
        }
    }

    @Test
    void on_a_view_the_latest_version_of_a_line_decides() throws Exception {
        declare("connector.v_dueorderevent");
        event("E1", "L-1", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
        event("E2", "L-1", "DELIVERED", TODAY.minusDays(2), "2026-10-19T09:00:00Z");
        event("E3", "L-2", "DELIVERED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
        event("E4", "L-2", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T09:00:00Z");
        sweep.sweepAt(NOW);
        assertThat(overdue()).containsExactly("L-2");
    }

    @Test
    void on_a_view_a_tie_on_two_deadlines_is_ambiguous_and_raises_nothing() throws Exception {
        declare("connector.v_dueorderevent");
        event("E1", "L-3", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
        event("E2", "L-3", "CONFIRMED", TODAY.minusDays(1), "2026-10-19T08:00:00Z");
        sweep.sweepAt(NOW);
        assertThat(overdue()).isEmpty();
    }

    /** The due sweep counts a blank status as a version, on a view as on a table: it never makes an older one current. */
    @Test
    void on_a_view_a_blank_latest_version_does_not_revive_an_older_one() throws Exception {
        declare("connector.v_dueorderevent");
        event("E1", "L-7", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
        event("E2", "L-7", "", TODAY.minusDays(2), "2026-10-19T09:00:00Z");
        sweep.sweepAt(NOW);
        assertThat(overdue()).isEmpty();
    }

    @Test
    void on_a_view_a_current_version_without_a_due_date_is_not_late() throws Exception {
        declare("connector.v_dueorderevent");
        event("E1", "L-4", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
        event("E2", "L-4", "CONFIRMED", null, "2026-10-19T09:00:00Z");
        sweep.sweepAt(NOW);
        assertThat(overdue()).isEmpty();
    }

    @Test
    void on_a_table_a_late_arriving_older_version_does_not_count() throws Exception {
        declare(EVENTS);
        event("E1", "L-5", "DELIVERED", TODAY.minusDays(2), "2026-10-19T09:00:00Z");
        event("E2", "L-5", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");   // ingested last
        sweep.sweepAt(NOW);
        assertThat(overdue()).isEmpty();
    }

    @Test
    void on_a_table_an_invalid_order_value_raises_nothing() throws Exception {
        declare(EVENTS);
        event("E1", "L-6", "CONFIRMED", TODAY.minusDays(2), "2026-10-19T08:00:00Z");
        event("E2", "L-6", "CONFIRMED", TODAY.minusDays(2), "yesterday");
        sweep.sweepAt(NOW);
        assertThat(overdue()).isEmpty();
    }
}
