package io.lumnik.hub.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * End-to-end on the spine: a data.row.changed event for a matching row makes the ProcessConsumer
 * emit exactly one decision.* event (dedup on replay); non-matching rows and disabled processes
 * emit nothing. The consumer runs on its own @Scheduled tick — assertions await it.
 */
@QuarkusTest
class ProcessConsumerIT {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long countDecisions(String type) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.event_outbox WHERE tenant_id = 4300 AND event_type = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Highest event_outbox id seen so far for the tenant — the watermark the "process" consumer
     *  must reach for its scheduled tick to have swept every event inserted up to this point. */
    private long maxEventId() {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COALESCE(MAX(id), 0) FROM core.event_outbox WHERE tenant_id = 4300")) {
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** The "process" consumer's cursor (core.event_consumer.last_event_id) — advances past a row
     *  the instant its scheduled tick (every 5s) has processed it, success or failure alike. */
    private long consumerCursor() {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT last_event_id FROM core.event_consumer WHERE consumer_name = 'process'")) {
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Poll every 200ms until {@code condition} holds or {@code timeoutMs} elapses, then return
     *  either way — the assertion right after the call site does the failing, unchanged from the
     *  fixed sleep this replaces: on the red path (condition never met) it waits the full ceiling
     *  and then fails exactly as it did before. */
    private void pollUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        } while (System.currentTimeMillis() < deadline);
    }

    /** Insert a data.row.changed event as the write path would (raw SQL, same envelope). */
    private void insertRowChanged(String table, String hash) {
        exec("INSERT INTO core.event_outbox (tenant_id, event_type, source, entity, entity_key, payload, occurred_at, created_at) "
           + "VALUES (4300, 'data.row.changed', 'it-seed', '" + table + "', '" + hash + "', "
           + "'{\"op\":\"insert\"}'::jsonb, NOW(), NOW())");
    }

    @BeforeEach
    void seed() {
        exec("DELETE FROM core.process_manifest WHERE tenant_id = 4300");
        exec("DELETE FROM core.event_outbox WHERE tenant_id = 4300");
        exec("DROP TABLE IF EXISTS connector.t_proc_e2e");
        exec("CREATE TABLE IF NOT EXISTS connector.t_proc_e2e "
           + "(tenant_id bigint, _row_hash text, code text, segment text, amount text)");
        exec("TRUNCATE connector.t_proc_e2e");
        exec("INSERT INTO connector.t_proc_e2e VALUES "
           + "(4300, 'hash-gold', 'C-1', 'GOLD', '150'), (4300, 'hash-silver', 'C-2', 'SILVER', '50')");
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
           + "(4300, 'gold-e2e', 'apiVersion: apps.lumnik.io/v1\n"
           + "kind: Process\nmetadata: { name: gold-e2e }\nspec:\n  scope: e2e\n"
           + "  on: { event: data.row.changed, table: connector.t_proc_e2e }\n"
           + "  when: [ segment=GOLD ]\n"
           + "  emit: { type: decision.gold-e2e, message: \"GOLD!\" }', "
           + "'e2e', 'connector.t_proc_e2e', 'decision.gold-e2e')");
    }

    @Test
    void matchingRowEmitsExactlyOneDecision_andNonMatchingEmitsNone() {
        insertRowChanged("connector.t_proc_e2e", "hash-gold");
        insertRowChanged("connector.t_proc_e2e", "hash-silver");

        await().atMost(20, SECONDS).untilAsserted(() ->
            assertThat(countDecisions("decision.gold-e2e")).isEqualTo(1L));

        // replay safety: a second identical trigger event (same original id can't repeat, so
        // simulate a re-delivery by re-inserting the SAME dedup target via the consumer path:
        // rewind the cursor and let it re-sweep)
        exec("UPDATE core.event_consumer SET last_xact_id = 0, last_event_id = 0 WHERE consumer_name = 'process'");
        // wait for the rewound cursor to be re-swept past these events; the count must STAY 1
        // (dedup_key blocks the double emit). Ceiling matches the old 12s sleep for slow machines.
        long maxId = maxEventId();
        pollUntil(() -> consumerCursor() >= maxId, 12_000);
        assertThat(countDecisions("decision.gold-e2e")).isEqualTo(1L);
    }

    @Test
    void disabledProcessEmitsNothing() {
        exec("UPDATE core.process_manifest SET enabled = FALSE WHERE tenant_id = 4300");
        insertRowChanged("connector.t_proc_e2e", "hash-gold");
        // wait for the tick to sweep past this event; ceiling matches the old 8s sleep.
        long maxId = maxEventId();
        pollUntil(() -> consumerCursor() >= maxId, 8_000);
        assertThat(countDecisions("decision.gold-e2e")).isZero();
    }

    private String payloadOf(String type) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT payload::text FROM core.event_outbox WHERE tenant_id = 4300 AND event_type = ?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void aTemplatedMessageSubstitutesFromTheRow_andCarriesFields() throws Exception {
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
           + "(4300, 'gold-hello', 'apiVersion: apps.lumnik.io/v1\n"
           + "kind: Process\nmetadata: { name: gold-hello }\nspec:\n  scope: e2e\n"
           + "  on: { event: data.row.changed, table: connector.t_proc_e2e }\n"
           + "  when: [ segment=GOLD ]\n"
           + "  emit: { type: decision.gold-hello, message: \"Client {code} en {segment}\" }', "
           + "'e2e', 'connector.t_proc_e2e', 'decision.gold-hello')");
        insertRowChanged("connector.t_proc_e2e", "hash-gold");

        await().atMost(20, SECONDS).untilAsserted(() ->
            assertThat(countDecisions("decision.gold-hello")).isEqualTo(1L));

        JsonNode n =
            new ObjectMapper().readTree(payloadOf("decision.gold-hello"));
        assertThat(n.get("message").asText()).isEqualTo("Client C-1 en GOLD");
        assertThat(n.get("fields").get("code").asText()).isEqualTo("C-1");
        assertThat(n.get("fields").get("segment").asText()).isEqualTo("GOLD");
    }

    @Test
    void aThresholdProcessFiresOnlyAboveTheThreshold() {
        exec("INSERT INTO core.process_manifest (tenant_id, name, yaml_text, scope, on_table, emit_type) VALUES "
           + "(4300, 'big-amount', 'apiVersion: apps.lumnik.io/v1\n"
           + "kind: Process\nmetadata: { name: big-amount }\nspec:\n  scope: e2e\n"
           + "  on: { event: data.row.changed, table: connector.t_proc_e2e }\n"
           + "  when: [ \"amount>100\" ]\n"
           + "  emit: { type: decision.big-amount, message: \"Gros montant\" }', "
           + "'e2e', 'connector.t_proc_e2e', 'decision.big-amount')");
        insertRowChanged("connector.t_proc_e2e", "hash-gold");     // amount 150 → fires
        insertRowChanged("connector.t_proc_e2e", "hash-silver");   // amount 50  → silent

        await().atMost(20, SECONDS).untilAsserted(() ->
            assertThat(countDecisions("decision.big-amount")).isEqualTo(1L));
    }
}
