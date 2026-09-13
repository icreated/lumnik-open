package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorDeadLetter;
import io.lumnik.hub.connector.repository.ConnectorDeadLetterRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTK Law 1 invariant guard: benign skips (Duplicate / PolicyExcluded) must NEVER be
 * routed to the dead-letter store.
 *
 * <p>Law 1 in {@code lm/test/otk/checks/reconcile.go ReconcileCounts} asserts
 * {@code recordsIn == recordsOut + dlqCount}. That equality depends on
 * {@code ConnectorDeadLetterRepository.countByRun} excluding {@code SkipReason.BENIGN_NAMES}
 * (Duplicate/PolicyExcluded). The exclusion is only correct while benign skips produce
 * NO dead-letter row — if a skip that was excluded from dlqCount is also not in
 * recordsOut, Law 1 would silently drop rows from the accounting.
 *
 * <p>Guard: ingest the same CSV file twice. The second run hits {@code ON CONFLICT DO NOTHING}
 * for every row (all hashes already present) — the duplicate skip scenario.
 * We assert:
 * <ol>
 *   <li>Second run {@code recordsIn == recordsOut}: ON CONFLICT DO NOTHING rows are counted as
 *       processed OUTPUT, so benign skips never create an unaccounted recordsIn-not-recordsOut gap.</li>
 *   <li>Table still holds exactly 2 rows (second run added nothing).</li>
 *   <li>No dead-letter rows at all for the second run ({@code findByRun} is empty) —
 *       benign skips must never appear in the DLQ table, neither in nor out of countByRun.</li>
 *   <li>{@code countByRun} for the second run is 0 (consistent with no DLQ rows).</li>
 * </ol>
 *
 * <p>What would make this test RED: any change that routes a duplicate-content-hash skip
 * through {@link DeadLetterCapture#capture} — for example, calling
 * {@code dlq.capture(..., new SkipReason.Duplicate(...))} inside {@code DynamicTableWriter}
 * or {@code ChunkExecutor}. In that case {@code findByRun} would return at least one entry
 * and the DLQ-empty assertion would fail — making Law 1 (ReconcileCounts) false-positive.
 */
@QuarkusTest
class BenignSkipNoDlqIT {

    @Inject ConnectorEngine engine;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorDeadLetterRepository dlqRepo;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    // The CSV connector prepends "t_" to the endpoint config "name" value when creating the table.
    private static final String TABLE_CONFIG_NAME = "benign_skip_guard";
    private static final String TABLE = "t_" + TABLE_CONFIG_NAME;

    @BeforeEach
    void dropTable() {
        execSql("DROP TABLE IF EXISTS connector." + TABLE);
        execSql("DELETE FROM connector.dynamic_table WHERE table_name = '" + TABLE + "'");
    }

    @Test
    void duplicate_skips_produce_no_dead_letter_rows() throws Exception {
        // CSV with two distinct rows (different hashes; avoid column name "id" — it collides
        // with DynamicTableWriter's BIGSERIAL system column).
        Path csv = Files.createTempFile("benign_skip_guard_", ".csv");
        csv.toFile().deleteOnExit();
        Files.writeString(csv, "row_id,email\n1,alice@x.com\n2,bob@x.com\n");

        UUID connectorId = persistCsvConnector(csv.toAbsolutePath().toString());

        // First run: both rows land.
        RunStatus first = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(first).as("first run must complete successfully").isNotInstanceOf(RunStatus.Failed.class);
        assertThat(scalar("SELECT count(*) FROM connector." + TABLE)).isEqualTo(2L);
        UUID firstRunId = latestRunExcluding(connectorId, null);

        // Second run: same file, all hashes already present — ON CONFLICT DO NOTHING on every row.
        RunStatus second = engine.runSync(connectorId, new RunTrigger.Manual(1L));
        assertThat(second).as("second run must complete successfully").isNotInstanceOf(RunStatus.Failed.class);

        // The second run id = the latest run that is NOT the first — robust to equal startedAt
        // timestamps (ties would make "order by startedAt desc" non-deterministic).
        UUID secondRunId = latestRunExcluding(connectorId, firstRunId);

        // ON CONFLICT DO NOTHING rows are counted as processed OUTPUT (not a loss), so a benign-only
        // run has recordsIn == recordsOut and zero dead-letters: Law 1 (recordsIn == recordsOut + dlq)
        // holds with NO unaccounted recordsIn-not-recordsOut gap. This is the crux — benign skips can
        // never break conservation because they land in recordsOut, not in a gap.
        long[] io = TenantContext.callAsSystem(() -> {
            var r = runs.findById(secondRunId);
            return new long[]{ r.getRecordsIn(), r.getRecordsOut() };
        });
        assertThat(io[1])
            .as("second run records_out must equal records_in — ON CONFLICT rows are counted as output, no gap")
            .isEqualTo(io[0]);

        // Table row count unchanged: second run contributed nothing new. The table still holds
        // exactly 2 rows, proving that ON CONFLICT DO NOTHING silently skipped all duplicate
        // hashes — the entire second run produced only benign skips.
        assertThat(scalar("SELECT count(*) FROM connector." + TABLE))
            .as("table must still hold exactly 2 rows after the duplicate run — second run added nothing")
            .isEqualTo(2L);

        // CORE INVARIANT: no dead-letter rows for the second run.
        // Benign skips (ON CONFLICT DO NOTHING) must never be routed to the DLQ table.
        // If DeadLetterCapture.capture() is ever called with SkipReason.Duplicate or
        // SkipReason.PolicyExcluded for these rows, findByRun will be non-empty and
        // this assertion will fail — making Law 1 (ReconcileCounts) false-positive.
        List<ConnectorDeadLetter> dlqRows = TenantContext.callAsSystem(() -> dlqRepo.findByRun(secondRunId, 50));
        assertThat(dlqRows)
            .as("benign skips must produce ZERO dead-letter rows (not even excluded ones)")
            .isEmpty();

        // Consistent view: countByRun must also be 0.
        long dlqCount = TenantContext.callAsSystem(() -> dlqRepo.countByRun(secondRunId));
        assertThat(dlqCount)
            .as("countByRun must be 0 for a run with only benign (duplicate) skips")
            .isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Latest run for the connector, optionally excluding one id — deterministic under startedAt ties
     *  (secondary sort by id) and fails loudly (orElseThrow) rather than NPE if no run exists. */
    private UUID latestRunExcluding(UUID connectorId, UUID excludeId) {
        return TenantContext.callAsSystem(() -> {
            var q = (excludeId == null)
                ? runs.find("connectorId = ?1 order by startedAt desc, id desc", connectorId)
                : runs.find("connectorId = ?1 and id != ?2 order by startedAt desc, id desc", connectorId, excludeId);
            return q.page(0, 1).firstResultOptional()
                    .map(r -> r.getId())
                    .orElseThrow(() -> new AssertionError("expected a run for connector " + connectorId));
        });
    }

    private UUID persistCsvConnector(String filePath) {
        return TenantContext.callAsSystem(() -> {
            try {
                utx.begin();

                Map<String, Object> cfg = new HashMap<>();
                cfg.put("kind", "csv-file");
                cfg.put("transport", Map.of("kind", "local", "path", filePath));
                cfg.put("parser", Map.of("has_header", true));

                Connector c = new Connector();
                c.setTenantId(1L);
                c.setName("benign-skip-guard-" + System.nanoTime());
                c.setTypeId("csv-file");
                c.setConfig(cfg);
                connectors.persist(c);
                connectors.flush();

                ConnectorEndpoint ep = new ConnectorEndpoint();
                ep.setTenantId(1L);
                ep.setConnectorId(c.getId());
                ep.setName("default");
                ep.setMethod("GET");
                ep.setPath(filePath);
                ep.setEnabled(true);
                ep.setConfig(Map.of("name", TABLE_CONFIG_NAME));
                endpoints.persist(ep);
                endpoints.flush();

                utx.commit();
                return c.getId();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception rb) { e.addSuppressed(rb); }
                throw new RuntimeException(e);
            }
        });
    }

    private void execSql(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
