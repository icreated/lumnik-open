package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.purgeWebhookEndpoints;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.seedWebhookEndpoint;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * É2.b T3 parity fix — ERROR mode must not silently de-dup in the gated path.
 *
 * <p>A subscription (gate ON) must never change ingestion success/failure. When {@code onConflict}
 * is {@code ERROR}, an intra-chunk duplicate {@code _row_hash} must surface as a unique-constraint
 * violation — identical to the un-gated path. The gated path must NOT de-dup ERROR-mode rows.
 */
@QuarkusTest
class GatedErrorModeIT {

    @Inject DynamicTableWriter writer;
    @Inject UserTransaction utx;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void setup() {
        cleanup();
    }

    @AfterEach
    void teardown() {
        cleanup();
    }

    private void cleanup() {
        // Admin datasource (BYPASSRLS / table owner) — no tenant GUC needed; a SET LOCAL here would
        // be a no-op (separate autocommit connection per execSql, outside a tx block).
        execSql(adminDs, "DELETE FROM core.event_outbox WHERE event_type = 'data.row.changed'");
        // One transaction with the endpoints locked first — WebhookConsumer ticks every 5 s and a
        // delivery landing mid-delete breaks the teardown (the 2026-07-24 nightly).
        purgeWebhookEndpoints(adminDs, "gem-%");
    }

    /**
     * Gate ON + ERROR mode + two identical rows → must throw (unique violation), exactly as the
     * un-gated ERROR path does. Before the fix, the gated path silently de-duped all modes and
     * the write succeeded — breaking parity.
     */
    @Test
    void error_mode_gated_intra_chunk_duplicate_throws_like_ungated() {
        // Gate ON: an active data.row.changed subscription for tenant 1.
        seedWebhookEndpoint(adminDs, utx, "gem-sub", "data.row.changed");

        String mappingId = "test_gem_" + System.nanoTime();
        MappingTarget.Table spec = new MappingTarget.Table(
            "tenant_1_data", mappingId, true, MappingTarget.OnConflict.ERROR, List.of());

        String hash = "h_err_" + mappingId;
        Map<String, Object> row = Map.of("email", "err@x.com", "name", "Err");
        Map<String, Object> sys = Map.of(
            "_row_hash", hash, "_tenant", 1L, "_mapping", mappingId,
            "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

        // Two IDENTICAL rows (same content hash) — ERROR mode must fail, not silently de-dup.
        // runInTxAsSystem rolls back on throw — otherwise the failed tx stays associated with the
        // thread and the next IT in this JVM fork dies with ARJUNA016051.
        assertThatThrownBy(() -> runInTxAsSystem(utx, () ->
            writer.write(spec, List.of(row, row), List.of(sys, sys), 1L)))
            .as("ERROR mode gated path must throw on intra-chunk duplicate _row_hash (parity with un-gated)")
            .isInstanceOf(RuntimeException.class);
    }

    /**
     * Sanity: a single (non-duplicate) row in ERROR mode with gate ON must succeed.
     * This guards against over-correction where ERROR mode fails even on clean input.
     */
    @Test
    void error_mode_gated_single_row_does_not_throw() {
        seedWebhookEndpoint(adminDs, utx, "gem-sub-single", "data.row.changed");

        String mappingId = "test_gem_single_" + System.nanoTime();
        MappingTarget.Table spec = new MappingTarget.Table(
            "tenant_1_data", mappingId, true, MappingTarget.OnConflict.ERROR, List.of());

        Map<String, Object> row = Map.of("email", "ok@x.com", "name", "Ok");
        Map<String, Object> sys = Map.of(
            "_row_hash", "h_ok_" + mappingId, "_tenant", 1L, "_mapping", mappingId,
            "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

        assertThatCode(() -> runInTxAsSystem(utx, () ->
            writer.write(spec, List.of(row), List.of(sys), 1L)))
            .as("single row in ERROR mode (gated) must not throw")
            .doesNotThrowAnyException();
    }

}
