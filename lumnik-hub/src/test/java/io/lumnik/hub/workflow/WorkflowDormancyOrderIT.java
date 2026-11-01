package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.LogProbe;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** #1588 on a raw table: the declared order, not the arrival, says which version is current. */
@QuarkusTest
class WorkflowDormancyOrderIT {

    @Inject WorkflowDormancySweep sweep;
    @Inject WorkflowManifestStore manifests;
    @Inject WorkflowObservationStore observations;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final long TENANT = 7521L;
    private static final String WF = "wf-order-it";
    private static final String TABLE = "connector.t_wf_order_it";
    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-order-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_wf_order_it, column: door_state, key: door, order: occurred_at }
          dormancy: { after: 1m, message: "{door}: {state}. Owner: {owner}" }
          states:
            - { code: OPEN, initial: true }
            - { code: CLOSED, terminal: true }
          transitions:
            - { from: [OPEN], to: CLOSED }
        """;

    private void sql(String s) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(s);
        }
    }

    private void event(String door, String state, String at) throws Exception {
        sql("INSERT INTO " + TABLE + " (tenant_id, door, door_state, occurred_at) VALUES (" + TENANT + ", '"
            + door + "', '" + state + "', " + (at == null ? "NULL" : "'" + at + "'") + ")");
    }

    @BeforeEach
    void seed() throws Exception {
        sql("DROP TABLE IF EXISTS " + TABLE);
        sql("CREATE TABLE " + TABLE + " (id bigserial PRIMARY KEY, tenant_id bigint NOT NULL, door text, "
            + "door_state text, occurred_at text, owner text, _ingested_at timestamptz NOT NULL DEFAULT now())");
        sql("DELETE FROM core.workflow_observation WHERE tenant_id = " + TENANT);
        sql("DELETE FROM core.workflow_transition WHERE tenant_id = " + TENANT);
        sql("DELETE FROM core.event_outbox WHERE tenant_id = " + TENANT);
        manifests.upsert(TENANT, new WorkflowManifestParser().parse(YAML), YAML, null);
    }

    private void declare(String yaml) {
        manifests.upsert(TENANT, new WorkflowManifestParser().parse(yaml), yaml, null);
    }

    private List<String> warnings() {
        return LogProbe.warnings(WorkflowDormancySweep.class, sweep::sweepNow).stream()
            .filter(w -> w.contains(WF)).toList();
    }

    /**
     * Removing spec.on.order releases what only an order could hold: put back with the same unreadable keys, the
     * condition is news again, not swallowed as "already said".
     */
    @Test
    void an_order_removed_then_restored_says_its_unreadable_keys_again() throws Exception {
        sweep.sweepNow();   // a clean tick: whatever an earlier test left held is released
        event("D9", "OPEN", "2026-10-26T10:00:00Z");
        event("D9", "CLOSED", "bad");
        assertThat(warnings()).anyMatch(w -> w.contains("are not watched"));
        declare(YAML.replace(", order: occurred_at", ""));
        sweep.sweepNow();
        declare(YAML);
        assertThat(warnings()).as("said again after the order came back").anyMatch(w -> w.contains("are not watched"));
    }

    @Test
    void an_order_removed_then_restored_says_its_refusal_again() throws Exception {
        sweep.sweepNow();
        sql("ALTER TABLE " + TABLE + " ALTER COLUMN occurred_at TYPE boolean USING NULL");
        assertThat(warnings()).anyMatch(w -> w.contains("spec.on.order occurred_at is boolean"));
        declare(YAML.replace(", order: occurred_at", ""));
        sweep.sweepNow();
        declare(YAML);
        assertThat(warnings()).as("said again after the order came back")
            .anyMatch(w -> w.contains("spec.on.order occurred_at is boolean"));
    }

    /**
     * Task 7 of the plan, carried out here: a cited column dropped between the catalog read and the statement (an
     * entity re-applied in between) is a 42703 once; the sweep reads the catalog again, renders it empty, and the
     * alert still fires. Without the rebuild the workflow would stand down and say nothing about the door.
     */
    @Test
    void a_cited_column_dropped_mid_tick_rebuilds_once_and_the_alert_still_fires() throws Exception {
        event("D10", "OPEN", "2026-10-26T10:00:00Z");
        sql("UPDATE " + TABLE + " SET owner = 'Dr. Lina Moreau' WHERE door = 'D10'");
        sweep.sweepNow();
        sql("UPDATE core.workflow_observation SET since = NOW() - INTERVAL '2 minutes' WHERE tenant_id = " + TENANT);
        AtomicBoolean dropped = new AtomicBoolean();
        sweep.beforeRead(() -> {
            if (dropped.compareAndSet(false, true)) {
                try {
                    sql("ALTER TABLE " + TABLE + " DROP COLUMN owner");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        try {
            sweep.sweepNow();
        } finally {
            sweep.beforeRead(() -> {});
        }
        assertThat(dormantMessages()).containsExactly("D10: OPEN. Owner: ");
    }

    /** An order column dropped mid-tick is the order's problem, said as such: the rerun reads the catalog again. */
    @Test
    void an_order_column_dropped_mid_tick_stands_the_workflow_down_on_the_order() throws Exception {
        sweep.sweepNow();   // a clean tick: whatever an earlier test left held is released
        event("D13", "OPEN", "2026-10-26T10:00:00Z");
        AtomicBoolean dropped = new AtomicBoolean();
        sweep.beforeRead(() -> {
            if (dropped.compareAndSet(false, true)) {
                try {
                    sql("ALTER TABLE " + TABLE + " DROP COLUMN occurred_at");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        List<String> said;
        try {
            said = warnings();
        } finally {
            sweep.beforeRead(() -> {});
        }
        assertThat(said).anyMatch(w -> w.contains("spec.on.order occurred_at is not a column"));
        assertThat(observations.loadAll(TENANT, WF)).as("nothing read, nothing written").doesNotContainKey("D13");
    }

    /**
     * A column the declaration stopped citing is released, whether nothing else is cited or another column took its
     * place: cited again while still missing, it is said again — the declaration now applied never heard it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{doc_key}: {state}", "{door}: {state}. Owner: {occurred_at}"})
    void a_column_no_longer_cited_is_released_and_said_again_when_cited_again(String without) throws Exception {
        sweep.sweepNow();   // a clean tick: whatever an earlier test left held is released
        sql("ALTER TABLE " + TABLE + " DROP COLUMN owner");
        assertThat(warnings()).anyMatch(w -> w.contains("cites owner"));
        declare(YAML.replace("\"{door}: {state}. Owner: {owner}\"", "\"" + without + "\""));
        sweep.sweepNow();
        declare(YAML);
        assertThat(warnings()).as("cited again, still missing").anyMatch(w -> w.contains("cites owner"));
    }

    /**
     * A workflow this sweep no longer watches — its dormancy removed, disabled, deleted — forgets what was said of it:
     * watched again with the same condition, it says it again.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dormancy removed", "disabled", "deleted"})
    void a_workflow_no_longer_swept_forgets_what_was_said_of_it(String how) throws Exception {
        sweep.sweepNow();   // a clean tick: whatever an earlier test left held is released
        sql("ALTER TABLE " + TABLE + " DROP COLUMN owner");
        assertThat(warnings()).anyMatch(w -> w.contains("cites owner"));
        switch (how) {
            case "dormancy removed" ->
                declare(YAML.replace("  dormancy: { after: 1m, message: \"{door}: {state}. Owner: {owner}\" }\n", ""));
            case "disabled" -> manifests.setEnabled(TENANT, WF, false);
            default -> manifests.delete(TENANT, WF);
        }
        sweep.sweepNow();
        if (how.equals("disabled")) manifests.setEnabled(TENANT, WF, true);
        else declare(YAML);
        assertThat(warnings()).as("watched again, still missing").anyMatch(w -> w.contains("cites owner"));
    }

    @Test
    void a_column_still_cited_and_still_missing_is_said_once() throws Exception {
        sweep.sweepNow();
        sql("ALTER TABLE " + TABLE + " DROP COLUMN owner");
        assertThat(warnings()).anyMatch(w -> w.contains("cites owner"));
        assertThat(warnings()).as("the next tick: already said").noneMatch(w -> w.contains("cites owner"));
    }

    private List<String> dormantMessages() throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT payload->>'message' FROM core.event_outbox WHERE tenant_id = ? AND event_type = ? ORDER BY id")) {
            ps.setLong(1, TENANT);
            ps.setString(2, "decision.dormant." + WF);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    /**
     * A source column may carry the name of one of the statement's own aliases (x0 is a sensor axis as often as
     * anything): picking the current version still keys on the key, not on the cited column aliased x0.
     */
    @Test
    void a_key_named_like_a_projection_alias_still_keys_the_documents() throws Exception {
        sql("ALTER TABLE " + TABLE + " RENAME COLUMN door TO x0");
        declare(YAML.replace("key: door", "key: x0")
            .replace("\"{door}: {state}. Owner: {owner}\"", "\"Owner: {owner}\""));
        sql("INSERT INTO " + TABLE + " (tenant_id, x0, door_state, occurred_at, owner) VALUES"
            + " (" + TENANT + ", 'D11', 'OPEN', '2026-10-26T10:00:00Z', 'Dr. Lina Moreau'),"
            + " (" + TENANT + ", 'D12', 'OPEN', '2026-10-26T10:00:00Z', 'Dr. Lina Moreau')");
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF)).as("two doors, one owner").containsOnlyKeys("D11", "D12");
    }

    /** A relation that is not there stands the workflow down; its cited columns are not "gone" from it. */
    @Test
    void a_missing_relation_does_not_say_its_cited_columns_render_empty() throws Exception {
        declare(YAML.replace(", order: occurred_at", ""));
        sweep.sweepNow();   // a clean tick: whatever an earlier test left held is released
        sql("DROP TABLE " + TABLE);
        assertThat(warnings()).anyMatch(w -> w.contains("standing down")).noneMatch(w -> w.contains("renders empty"));
    }

    @Test
    void a_late_arriving_older_event_does_not_reopen_the_door() throws Exception {
        event("D1", "CLOSED", "2026-10-26T10:05:00Z");
        event("D1", "OPEN", "2026-10-26T10:00:00Z");      // ingested last, happened first
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("D1").state()).isEqualTo("CLOSED");
    }

    @Test
    void one_invalid_order_makes_the_key_unreadable_even_after_a_later_valid_version() throws Exception {
        event("D2", "OPEN", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        sql("UPDATE core.workflow_observation SET since = NOW() - INTERVAL '2 hours' WHERE tenant_id = " + TENANT);
        event("D2", "CLOSED", "not-a-date");
        event("D2", "CLOSED", "2026-10-26T10:05:00Z");
        Instant suspended = Instant.now();
        sweep.sweepNow();
        WorkflowObservationStore.Observation obs = observations.loadAll(TENANT, WF).get("D2");
        assertThat(obs.state()).as("unreadable: nothing moved").isEqualTo("OPEN");
        assertThat(obs.since()).as("suspended: its clock restarts").isAfterOrEqualTo(suspended.minusSeconds(1));
        sql("DELETE FROM " + TABLE + " WHERE occurred_at = 'not-a-date'");   // the repair
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("D2").state()).isEqualTo("CLOSED");
    }

    /** The tape keeps what happened before an unreadable period: suspending a document never hides its journal. */
    @Test
    void an_unreadable_document_keeps_its_journal() throws Exception {
        event("D7", "OPEN", "2026-10-26T10:00:00Z");
        sweep.sweepNow();
        event("D7", "CLOSED", "2026-10-26T10:05:00Z");
        sweep.sweepNow();
        assertThat(observations.transitions(TENANT, null, WF, "D7", 10)).hasSize(1);
        event("D7", "OPEN", "garbage");
        sweep.sweepNow();
        var tape = observations.transitions(TENANT, null, WF, "D7", 10);
        assertThat(tape).hasSize(1);
        assertThat(tape.get(0).enteredFromAt()).isBeforeOrEqualTo(tape.get(0).observedAt());
    }

    /** NaN and infinity are not positions: a version carrying one makes its document unreadable. */
    @Test
    void a_non_finite_order_value_is_no_position() throws Exception {
        sql("ALTER TABLE " + TABLE + " ALTER COLUMN occurred_at TYPE timestamptz USING occurred_at::timestamptz");
        event("D8", "OPEN", "2026-10-26T10:00:00Z");
        event("D8", "CLOSED", "infinity");
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("D8")).as("never observed: unreadable from the start").isNull();
    }

    @Test
    void a_null_order_is_an_unknown_position() throws Exception {
        event("D3", "OPEN", "2026-10-26T10:00:00Z");
        event("D3", "CLOSED", null);
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF)).as("never observed: unreadable from the start")
            .doesNotContainKey("D3");
    }

    @Test
    void a_tie_on_a_table_is_broken_by_ingestion() throws Exception {
        event("D4", "OPEN", "2026-10-26T10:00:00Z");
        event("D4", "CLOSED", "2026-10-26T11:00:00+01:00");   // the same instant, ingested later
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF).get("D4").state()).isEqualTo("CLOSED");
    }

    @Test
    void an_order_column_of_a_refused_type_stands_the_workflow_down() throws Exception {
        event("D5", "OPEN", "2026-10-26T10:00:00Z");
        sql("ALTER TABLE " + TABLE + " ALTER COLUMN occurred_at TYPE boolean USING NULL");
        sweep.sweepNow();
        assertThat(observations.loadAll(TENANT, WF)).as("nothing read, nothing written").isEmpty();
    }
}
