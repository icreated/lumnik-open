package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.joinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markedKeys;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createMatchSourceTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.entityManifest;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #701 phase 6, design doc §5 and §7 test 9 (as corrected: the watermark is placed BEFORE the missed
 * rows, or absent entirely — never past them, which would make {@code _ingested_at > watermark}
 * exclude them by construction and prove nothing). {@link SearchProjectionReconciler} is the periodic
 * safety net that catches up rows a {@code ConnectorRunCompleted} event never picked up (a reaped run
 * with no successor run on that table).
 *
 * <p>Same fixture idiom as {@code SearchProjectionIngestionIT} (both reuse {@link
 * SearchProjectionMarkItSupport}): a hand-built {@code connector.t_*} table with {@code tenant_id} +
 * {@code _ingested_at}, since the reconciler scans exactly that column the way {@code
 * DynamicTableWriter} stamps it on every real row. The reconciler's own observer method ({@code
 * sweep()}) is called directly rather than waiting on {@code @Scheduled}'s own timer — the cron-tick
 * delivery mechanism itself is not what these tests exist to prove.
 */
@QuarkusTest
class SearchProjectionReconcilerIT {

    private static final String REASON = "ingestion-run";

    @Inject SearchProjectionReconciler reconciler;
    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** Force-rewinds the watermark backward, past {@link SearchProjectionWatermarkStore#advance}'s own
     *  never-go-backward guard — simulates the "run completion event never fired for these later rows"
     *  gap on a table that already has SOME history, per §7 test 9's corrected shape. */
    private void rewindWatermark(long tenant, String qualifiedTable, Instant to) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE core.search_projection_mark_watermark SET watermark = ? "
               + "WHERE tenant_id = ? AND table_name = ?")) {
            ps.setTimestamp(1, Timestamp.from(to));
            ps.setLong(2, tenant);
            ps.setString(3, qualifiedTable);
            int updated = ps.executeUpdate();
            assertThat(updated).as("watermark row must already exist to rewind it").isEqualTo(1);
        }
    }

    private Instant watermarkValue(long tenant, String qualifiedTable) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT watermark FROM core.search_projection_mark_watermark "
               + "WHERE tenant_id = ? AND table_name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, qualifiedTable);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        }
    }

    // -- Design doc §7 test 9 -------------------------------------------------------------------------

    @Test
    void missedRowsWithNoWatermarkAtAllAreMarkedByTheSweep() throws Exception {
        long tenant = 701_601;
        String entityName = "SearchRecon701A" + System.nanoTime();
        String table = "t_search_recon_701a_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        String indexName = unique("mark-recon-a");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        // Rows land directly, as DynamicTableWriter would commit them mid-run -- but the run that
        // wrote them is simulated as reaped: ConnectorRunCompleted never fires, so
        // SearchProjectionRunHook.onRunCompleted is never called and no watermark row is created.
        insertRow(adminDs, table, tenant, "C1", "Alpha");
        insertRow(adminDs, table, tenant, "C2", "Beta");
        insertRow(adminDs, table, tenant, "C1", "Alpha again");

        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table))
            .as("precondition: no watermark row exists yet for this table").isZero();

        reconciler.sweep();

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("2 distinct match tuples among 3 rows, discovered and marked purely from "
              + "core.search_index -> entity -> EntityManifest.sources(), never from the watermark table")
            .isEqualTo(2L);
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table))
            .as("a successful sweep leaves a watermark row behind, same as the event-triggered hook")
            .isEqualTo(1L);
    }

    @Test
    void missedRowsWithWatermarkRewoundBeforeThemAreMarkedByTheSweep() throws Exception {
        long tenant = 701_602;
        String entityName = "SearchRecon701B" + System.nanoTime();
        String table = "t_search_recon_701b_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        String indexName = unique("mark-recon-b");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        // First batch, marked normally via the event hook -- creates a real watermark row.
        insertRow(adminDs, table, tenant, "C1", "Alpha");
        hook.processTenant(tenant);
        assertThat(markCount(adminDs, tenant, indexName, REASON)).isEqualTo(1L);

        Instant afterFirstBatch = watermarkValue(tenant, "connector." + table);

        // Second batch, simulating a run whose completion event never fired (reaped): the rows are
        // durably committed, but nothing advances the watermark past them.
        insertRow(adminDs, table, tenant, "C2", "Beta");
        insertRow(adminDs, table, tenant, "C3", "Gamma");

        // Rewind the watermark to strictly BEFORE the second batch's _ingested_at (never past it --
        // that would make _ingested_at > watermark exclude the missed rows by construction and prove
        // nothing, exactly the bug the issue review caught in an earlier draft of this test).
        rewindWatermark(tenant, "connector." + table, afterFirstBatch.minus(1, ChronoUnit.SECONDS));

        reconciler.sweep();

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("first batch's 1 mark + second batch's 2 new marks (C2, C3), the missed rows caught up")
            .isEqualTo(3L);
    }

    @Test
    void tableWithNoWatermarkAndNoSearchIndexIsNeverDiscovered() throws Exception {
        long tenant = 701_603;
        String entityName = "SearchRecon701C" + System.nanoTime();
        String table = "t_search_recon_701c_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        // Deliberately: no SearchIndex applied over this entity, and no watermark row for the table.

        insertRow(adminDs, table, tenant, "C1", "Alpha");

        reconciler.sweep();

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = ?")) {
            ps.setLong(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1))
                    .as("discovery starts from core.search_index, not from scanning every "
                      + "connector.t_* table blindly -- no SearchIndex means the sweep never even looks")
                    .isZero();
            }
        }
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table))
            .as("a table the sweep never discovers gains no watermark row either").isZero();
    }

    @Test
    void tableAlreadyCaughtUpIsANoOpForTheSweep() throws Exception {
        long tenant = 701_604;
        String entityName = "SearchRecon701D" + System.nanoTime();
        String table = "t_search_recon_701d_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);

        yaml(tenant).body(entityManifest(entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        String indexName = unique("mark-recon-d");
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        insertRow(adminDs, table, tenant, "C1", "Alpha");
        insertRow(adminDs, table, tenant, "C2", "Beta");
        hook.processTenant(tenant);

        long marksAfterRun = markCount(adminDs, tenant, indexName, REASON);
        Instant watermarkAfterRun = watermarkValue(tenant, "connector." + table);
        assertThat(marksAfterRun).isEqualTo(2L);

        // Nothing new was ingested; the watermark already sits at or past every row's _ingested_at --
        // OR still conservatively capped behind them (commitOrderSafeCutoff, #671 code review: the
        // cap is cluster-wide, so an unrelated transaction open anywhere else at the first run's own
        // scan instant can legitimately leave watermarkAfterRun a little behind the true max). Either
        // way, re-scanning the same already-marked rows is idempotent -- no new marks either way.
        reconciler.sweep();

        assertThat(markCount(adminDs, tenant, indexName, REASON))
            .as("already caught up -- the sweep is a quiet no-op, no new marks").isEqualTo(marksAfterRun);
        assertThat(watermarkValue(tenant, "connector." + table))
            .as("watermark never regresses -- it may still legitimately catch up further if the first "
              + "run's own advance was conservatively capped behind unrelated concurrent activity")
            .isAfterOrEqualTo(watermarkAfterRun);
    }

    // #847: the sweep reaches a lookup table too — with the event hook never fired.
    @Test
    void aLookupRowIsMarkedBySweepAloneThroughTheSourcesThatMapFrom() throws Exception {
        long tenant = 847_501;
        String n = String.valueOf(System.nanoTime());
        String products = "t_recon_lk_p_" + n, lookup = "t_recon_lk_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "ReconLookup" + n;
        yaml(tenant).body(joinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("mark-recon-lk");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (847501, 'P1', 'C1', 'n')");
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (847501, 'P2', 'C2', 'n')");
        reconciler.sweep();                                    // settles the product marks and every watermark
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = 847501");

        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (847501, 'C1', 'Pumps')");
        reconciler.sweep();                                    // no ConnectorRunCompleted: the sweep alone

        assertThat(markedKeys(adminDs, tenant, index, "lookup-change"))
            .containsExactly(MatchKeyEncoding.encode(List.of("P1")));
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + lookup)).isEqualTo(1L);
    }

    // #850: the sweep catches a lookup row whose event was missed, for a resolution entity
    @Test
    void theSweepMarksTheResolvedEntitiesOfALookupRowNoEventAnnounced() throws Exception {
        long tenant = 850_601;
        String n = String.valueOf(System.nanoTime());
        String products = "t_rr_p_" + n, lookup = "t_rr_c_" + n, entity = "ResSweep" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("res-sweep");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        UUID e1 = UUID.randomUUID();
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (850601, 'P1', 'C1', 'n')");
        insertIdentity(adminDs, tenant, entity, products, "P1", e1);
        reconciler.sweep();                                    // settles the watermarks
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = 850601");
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (850601, 'C1', 'Pumps')");

        reconciler.sweep();                                    // no ConnectorRunCompleted was ever fired

        assertThat(markedKeys(adminDs, tenant, index, "lookup-change")).containsExactly(e1.toString());
    }
}
