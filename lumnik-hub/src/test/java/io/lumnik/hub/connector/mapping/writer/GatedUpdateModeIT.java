package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.purgeWebhookEndpoints;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.seedWebhookEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * É2.b Task 3 review — Bug A: intra-chunk duplicate {@code _row_hash} in UPDATE mode.
 *
 * <p>Two identical rows in one chunk share their content hash. The gated path collapses the
 * chunk into ONE multi-row {@code INSERT … ON CONFLICT (tenant_id, _row_hash) DO UPDATE …}; Postgres then
 * raises "ON CONFLICT DO UPDATE command cannot affect row a second time" and the run fails.
 *
 * <p>The fix de-dups by {@code _row_hash} (first occurrence wins) before building the INSERT:
 * no throw, the row lands once, and exactly ONE {@code data.row.changed} event is emitted.
 */
@QuarkusTest
class GatedUpdateModeIT {

    @Inject DynamicTableWriter writer;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void cleanOutbox() {
        cleanup();
    }

    // The webhook_endpoint + outbox are global (no tenant session here). Leave none behind, or a
    // leftover gum-* endpoint flips the gate ON for sibling ITs sharing this JVM (e.g.
    // DataRowChangedIT.unsubscribedRunEmitsNothing).
    @AfterEach
    void cleanupAfter() {
        cleanup();
    }

    private void cleanup() {
        // Admin datasource (BYPASSRLS / table owner) — no tenant GUC needed; a SET LOCAL here would
        // be a no-op (separate autocommit connection per execSql, outside a tx block).
        execSql(adminDs, "DELETE FROM core.event_outbox WHERE event_type = 'data.row.changed'");
        // One transaction with the endpoints locked first — WebhookConsumer ticks every 5 s and a
        // delivery landing mid-delete breaks the teardown (the 2026-07-24 nightly).
        purgeWebhookEndpoints(adminDs, "gum-%");
    }

    @Test
    void update_mode_tolerates_intra_chunk_duplicate_and_emits_one_event() {
        // Gate ON: an active data.row.changed subscription for the tenant.
        seedWebhookEndpoint(adminDs, utx, "gum-sub", "data.row.changed");

        String mappingId = "test_gum_" + System.nanoTime();
        MappingTarget.Table spec = new MappingTarget.Table(
            "tenant_1_data", mappingId, true, MappingTarget.OnConflict.UPDATE, List.of());

        String hash = "h_dup_" + mappingId;
        Map<String, Object> row = Map.of("email", "dup@x.com", "name", "Dup");
        Map<String, Object> sys = Map.of(
            "_row_hash", hash, "_tenant", 1L, "_mapping", mappingId,
            "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

        // Two IDENTICAL rows (same content hash) in one chunk.
        assertThatCode(() -> runInTxAsSystem(utx, () ->
            writer.write(spec, List.of(row, row), List.of(sys, sys), 1L)))
            .as("UPDATE mode must not throw on an intra-chunk duplicate _row_hash")
            .doesNotThrowAnyException();

        long rows = countRows("tenant_1_data." + mappingId, hash);
        assertThat(rows).as("the de-duped row lands exactly once").isEqualTo(1);

        long events = countEvents(hash);
        assertThat(events).as("exactly one data.row.changed event for one unique content").isEqualTo(1);
    }

    /**
     * Multi-sub-batch coverage: more new rows than one gated sub-batch (EMIT_SUB_BATCH=400) so the
     * loop runs ≥2 iterations and the per-sub-batch outbox flush is exercised. Every genuinely-new
     * row must still produce exactly one event — no boundary row dropped.
     */
    @Test
    void multi_sub_batch_emits_one_event_per_new_row() {
        seedWebhookEndpoint(adminDs, utx, "gum-multi", "data.row.changed");

        String mappingId = "test_gum_multi_" + System.nanoTime();
        MappingTarget.Table spec = new MappingTarget.Table(
            "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());

        int total = 401; // 401 > 400 → two sub-batches
        List<Map<String, Object>> rows = new ArrayList<>(total);
        List<Map<String, Object>> syss = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            rows.add(Map.of("email", "u" + i + "@x.com", "name", "U" + i));
            syss.add(Map.of(
                "_row_hash", "h_multi_" + i + "_" + mappingId, "_tenant", 1L, "_mapping", mappingId,
                "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test"));
        }

        assertThatCode(() -> runInTxAsSystem(utx, () ->
            writer.write(spec, rows, syss, 1L)))
            .doesNotThrowAnyException();

        assertThat(countAllDataEvents())
            .as("one data.row.changed event per genuinely-new row, across sub-batch boundaries")
            .isEqualTo(total);
    }

    /**
     * When the writer is invoked without a {@code _run_id} (internal/test paths), the event payload
     * must OMIT run_id — not serialize the literal string "null", which is an ambiguous value for
     * consumers.
     */
    @Test
    void eventOmitsRunIdWhenAbsent() throws Exception {
        seedWebhookEndpoint(adminDs, utx, "gum-norunid", "data.row.changed");

        String mappingId = "test_gum_norun_" + System.nanoTime();
        MappingTarget.Table spec = new MappingTarget.Table(
            "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());

        String hash = "h_norun_" + mappingId;
        Map<String, Object> row = Map.of("email", "n@x.com", "name", "N");
        // sys WITHOUT _run_id
        Map<String, Object> sys = Map.of(
            "_row_hash", hash, "_tenant", 1L, "_mapping", mappingId,
            "_mapping_version", 1, "_source", "test");

        runInTxAsSystem(utx, () ->
            writer.write(spec, List.of(row), List.of(sys), 1L));

        JsonNode p =
            new ObjectMapper().readTree(payloadFor(hash));
        assertThat(p.has("run_id"))
            .as("run_id is omitted when no _run_id is present (never the string \"null\")").isFalse();
        assertThat(p.get("op").asText()).isEqualTo("insert");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String payloadFor(String hash) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT payload::text FROM core.event_outbox "
                 + "WHERE event_type = 'data.row.changed' AND entity_key = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long countRows(String fqn, String hash) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM " + fqn + " WHERE _row_hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long countAllDataEvents() {
        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT count(*) FROM core.event_outbox WHERE event_type = 'data.row.changed'")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long countEvents(String hash) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.event_outbox "
                 + "WHERE event_type = 'data.row.changed' AND entity_key = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
