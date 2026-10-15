package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyMatchEntityAndIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.MatchFixture;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermark;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static org.assertj.core.api.Assertions.assertThat;

/** #730: a table with nothing new after its watermark must still move the watermark forward (to the
 *  bound captured before the scan), or an idle table freezes every asOf built on it. */
@QuarkusTest
class SearchProjectionIdleWatermarkIT {

    @Inject SearchProjectionReconciler reconciler;
    @Inject SearchProjectionRunHook runHook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void anEmptyTableGetsAWatermarkAtTheBoundInsteadOfNone() throws Exception {
        long tenant = 730_501;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Empty");
        Instant before = Instant.now().minusSeconds(5);

        reconciler.sweep();

        assertThat(watermarkRowCount(adminDs, tenant, "connector." + f.table())).isEqualTo(1L);
        assertThat(watermark(adminDs, tenant, "connector." + f.table())).isAfter(before);
    }

    @Test
    void aQuietTableFollowsTheBoundOnTheNextSweepAndNeverGoesBackwards() throws Exception {
        long tenant = 730_502;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Quiet");
        insertRow(adminDs, f.table(), tenant, "C1", "Alpha");
        reconciler.sweep();                                   // scans the row, watermark = its _ingested_at
        Instant afterRow = watermark(adminDs, tenant, "connector." + f.table());

        Thread.sleep(2_200);                                  // outrun the 1 s margin applied on both bound terms
        reconciler.sweep();                                   // nothing new: idle rule applies
        Instant afterIdle = watermark(adminDs, tenant, "connector." + f.table());

        assertThat(afterIdle).isAfter(afterRow);
        reconciler.sweep();
        assertThat(watermark(adminDs, tenant, "connector." + f.table())).isAfterOrEqualTo(afterIdle);
    }

    @Test
    void aRowCommittedAfterTheIdleAdvanceIsStillScanned() throws Exception {
        long tenant = 730_503;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Late");
        reconciler.sweep();                                   // idle advance
        insertRow(adminDs, f.table(), tenant, "C9", "Late row");  // _ingested_at = now() > the advanced watermark
        reconciler.sweep();
        assertThat(markCount(adminDs, tenant, f.index(), "ingestion-run")).isEqualTo(1L);
    }

    /** A cap read before a younger transaction opened cannot see the older one: the younger's committed row
     *  used to drag the watermark past the older, still-open transaction's start, hiding its rows forever
     *  once it committed. The bound is read fresh, right before the table's own scan. */
    @Test
    void aYoungerCommittedRowNeverDragsTheWatermarkPastAnOlderOpenTransaction() throws Exception {
        long tenant = 730_504;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Race");
        Instant olderStart;
        try (Connection older = adminDs.getConnection()) {
            older.setAutoCommit(false);
            try (Statement st = older.createStatement()) {
                st.execute("INSERT INTO connector." + f.table() + " (tenant_id, src_code, src_name) "
                    + "VALUES (" + tenant + ", 'OLD', 'older tx row')");
                try (ResultSet rs = st.executeQuery("SELECT now()")) {     // == this transaction's start
                    rs.next();
                    olderStart = rs.getTimestamp(1).toInstant();
                }
            }
            Thread.sleep(50);
            insertRow(adminDs, f.table(), tenant, "YOUNG", "younger tx row");   // commits: _ingested_at > olderStart

            runHook.processTable(tenant, f.table(), runHook.targetsByTable(tenant).get(f.table()));

            assertThat(watermark(adminDs, tenant, "connector." + f.table()))
                .as("the watermark stays behind the still-open older transaction")
                .isBefore(olderStart);
            older.commit();
        }

        reconciler.sweep();

        assertThat(markCount(adminDs, tenant, f.index(), "ingestion-run"))
            .as("both rows — the older transaction's row was never hidden behind the watermark")
            .isEqualTo(2L);
    }
}
