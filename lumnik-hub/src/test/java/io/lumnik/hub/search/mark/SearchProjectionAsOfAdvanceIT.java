package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore;
import io.lumnik.hub.search.projection.SearchProjectionAsOfAdvancer;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.joinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyResolutionEntity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyMatchEntityAndIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.awaitSafeBoundPast;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRun;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.runFence;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.MatchFixture;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermark;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/** #730: the advancer against a real Postgres — real marks, real watermarks, real open transactions. */
@QuarkusTest
class SearchProjectionAsOfAdvanceIT {

    @Inject SearchProjectionAsOfAdvancer advancer;
    @Inject SearchProjectionReconciler reconciler;
    @Inject SearchProjectionRunHook runHook;
    @Inject SearchProjectionMarkStore markStore;
    @Inject ResolutionCoverageStore coverage;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void anIdleMatchPairAdvancesOnceItsQueueIsEmpty() throws Exception {
        long tenant = 730_701;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Idle");
        Instant before = Instant.now().minusSeconds(5);
        reconciler.sweep();                                    // idle rule gives the table a watermark
        markStore.deleteAllFor(tenant, f.index());             // nothing pending
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(tenant, f.index(), sink, "gen-1")).isPresent();

        assertThat(sink.advances).hasSize(1);
        assertThat(sink.advances.get(0)).isAfter(before);
    }

    @Test
    void aPendingMarkBlocksTheAdvanceUntilItIsApplied() throws Exception {
        long tenant = 730_702;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Pending");
        insertRow(adminDs, f.table(), tenant, "C1", "Alpha");  // changed at 10:00 ...
        reconciler.sweep();                                    // ... marked only when the sweep runs (10:30)
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(tenant, f.index(), sink, "gen-1")).isEmpty();
        assertThat(sink.advances).isEmpty();

        markStore.deleteAllFor(tenant, f.index());             // catch-up applied it
        assertThat(advancer.advance(tenant, f.index(), sink, "gen-1")).isPresent();
        assertThat(sink.advances).hasSize(1);
    }

    private record JoinFx(long tenant, String products, String lookup, String index) {}

    /** A match entity joining a lookup, one product row, a SearchIndex over it — nothing scanned yet. */
    private JoinFx joinFixture(long tenant) throws Exception {
        String n = String.valueOf(System.nanoTime());
        String products = "t_asj_p_" + n, lookup = "t_asj_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "AsOfJoin" + n;
        yaml(tenant).body(joinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("asof-join");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (" + tenant + ", 'P1', 'C1', 'n')");
        return new JoinFx(tenant, products, lookup, index);
    }

    private Instant lookupIngestedAt(JoinFx f) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT max(_ingested_at) FROM connector." + f.lookup())) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    @Test
    void aLookupNeverScannedStopsTheAdvanceEvenWhenEverySourceWasScanned() throws Exception {
        JoinFx f = joinFixture(847_401);
        // Scan the products table ONLY: every direct source has a watermark, the lookup has none. Were the lookup
        // not part of the bound, nothing would stop the advance.
        runHook.processTable(f.tenant(), f.products(), runHook.targetsByTable(f.tenant()).get(f.products()));
        markStore.deleteAllFor(f.tenant(), f.index());
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).isEmpty();   // no watermark for the lookup yet
        assertThat(sink.advances).isEmpty();
    }

    @Test
    void theBoundStaysBehindAnUnscannedLookupRowAndMovesOnlyOnceItsMarksAreDrained() throws Exception {
        JoinFx f = joinFixture(847_402);
        reconciler.sweep();                                    // watermarks for products and (idle rule) lookup
        markStore.deleteAllFor(f.tenant(), f.index());         // drained
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (847402, 'C1', 'Pumps')");
        Instant row = lookupIngestedAt(f);
        RecordingSink sink = new RecordingSink();

        Instant lookupWatermark = watermark(adminDs, f.tenant(), "connector." + f.lookup());
        assertThat(lookupWatermark).as("the lookup's own watermark has not seen the row").isBefore(row);
        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).hasValueSatisfying(b ->
            assertThat(b).as("so the bound cannot pass it").isBeforeOrEqualTo(lookupWatermark));

        reconciler.sweep();                                    // scans the lookup row: marks are now pending
        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).as("marks pending").isEmpty();

        awaitSafeBoundPast(advancer::safeBound, row);
        reconciler.sweep();                                    // the watermark can now reach the row itself
        markStore.deleteAllFor(f.tenant(), f.index());         // catch-up applied them
        assertThat(watermark(adminDs, f.tenant(), "connector." + f.lookup())).as("the row is covered now")
            .isAfterOrEqualTo(row);
        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).isPresent();
    }

    private record ResolutionFx(String entity, String index) {}

    /** A {@code resolution:} entity with a SearchIndex over it and an empty queue. */
    private ResolutionFx resolutionFixture(long tenant, String label) throws Exception {
        String entity = "AsOfResolved" + label + System.nanoTime();
        String table = "t_asof_res_" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, entity, table);
        String index = unique("asof-res-" + label.toLowerCase(Locale.ROOT));
        yaml(tenant).body(searchIndex(index, entity, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        markStore.deleteAllFor(tenant, index);
        return new ResolutionFx(entity, index);
    }

    private static String entityType(String entity) {
        return entity.toLowerCase(Locale.ROOT);
    }

    @Test
    void anUncoveredRunningRunBlocksAtItsFence() throws Exception {
        long tenant = 748_601;
        ResolutionFx fx = resolutionFixture(tenant, "Running");
        UUID run = insertRun(adminDs, tenant, "Running", false);   // committed chunks of a run that has not ended are undecided
        Instant fence = runFence(adminDs, run);
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(tenant, fx.index(), sink, "gen-1")).isPresent();  // the fence bounds, it does not stop

        assertThat(sink.advances).hasSize(1);
        assertThat(sink.advances.get(0)).isBeforeOrEqualTo(fence);
    }

    @Test
    void aNullFenceUncoveredRunStopsTheAdvance() throws Exception {
        long tenant = 748_602;
        ResolutionFx fx = resolutionFixture(tenant, "Null");
        insertRun(adminDs, tenant, "Completed", true);             // predates V60: unknown bound
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(tenant, fx.index(), sink, "gen-1")).isEmpty();
        assertThat(sink.advances).isEmpty();
    }

    @Test
    void coveringTheRunReleasesTheAdvance() throws Exception {
        long tenant = 748_603;
        ResolutionFx fx = resolutionFixture(tenant, "Cover");
        UUID run = insertRun(adminDs, tenant, "Completed", false);
        Instant fence = runFence(adminDs, run);
        RecordingSink sink = new RecordingSink();
        advancer.advance(tenant, fx.index(), sink, "gen-1");
        assertThat(sink.advances.get(0)).isBeforeOrEqualTo(fence);

        coverage.cover(tenant, entityType(fx.entity()), List.of(run));
        awaitSafeBoundPast(advancer::safeBound, fence);            // the 1 s margin keeps safeBound behind a fresh fence
        assertThat(advancer.advance(tenant, fx.index(), sink, "gen-1")).hasValueSatisfying(b -> assertThat(b).isAfter(fence));
    }

    @Test
    void aFailedRunStaysBlockingUntilCovered() throws Exception {
        long tenant = 748_604;
        ResolutionFx fx = resolutionFixture(tenant, "Failed");
        UUID run = insertRun(adminDs, tenant, "Failed", false);    // no ConnectorRunCompleted fires for it
        Instant fence = runFence(adminDs, run);
        awaitSafeBoundPast(advancer::safeBound, fence);            // the clock alone would now pass the fence ...
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(tenant, fx.index(), sink, "gen-1")).hasValueSatisfying(b -> assertThat(b).isBeforeOrEqualTo(fence));
    }

    @Test
    void coverageOfAnotherEntityDoesNotRelease() throws Exception {
        long tenant = 748_605;
        ResolutionFx fx = resolutionFixture(tenant, "Mine");
        UUID run = insertRun(adminDs, tenant, "Completed", false);
        Instant fence = runFence(adminDs, run);
        coverage.cover(tenant, "some-other-entity", List.of(run));
        awaitSafeBoundPast(advancer::safeBound, fence);
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(tenant, fx.index(), sink, "gen-1")).hasValueSatisfying(b -> assertThat(b).isBeforeOrEqualTo(fence));
    }

    @Test
    void aTenantWithNoRunsAdvancesToTheSafeBound() throws Exception {
        long tenant = 748_606;
        ResolutionFx fx = resolutionFixture(tenant, "NoRuns");
        RecordingSink sink = new RecordingSink();
        Instant before = advancer.safeBound();

        assertThat(advancer.advance(tenant, fx.index(), sink, "gen-1")).hasValueSatisfying(b -> assertThat(b).isAfterOrEqualTo(before));
    }

    @Test
    void anOpenTransactionHoldsTheBoundBack() throws Exception {
        long tenant = 730_704;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Open");
        reconciler.sweep();
        markStore.deleteAllFor(tenant, f.index());
        RecordingSink sink = new RecordingSink();

        try (Connection open = adminDs.getConnection()) {
            open.setAutoCommit(false);
            Instant xactStart;
            try (Statement st = open.createStatement(); ResultSet rs = st.executeQuery("SELECT now()")) {
                rs.next();
                xactStart = rs.getTimestamp(1).toInstant();
            }
            Thread.sleep(50);
            advancer.advance(tenant, f.index(), sink, "gen-1");
            open.rollback();

            assertThat(sink.advances).hasSize(1);
            assertThat(sink.advances.get(0)).isBeforeOrEqualTo(xactStart.minusSeconds(1));
        }
    }

    private record ResFx(long tenant, String products, String lookup, String index, String entity) {}

    /** A resolution entity joining a lookup, one decided product, a SearchIndex over it — nothing scanned yet (#850). */
    private ResFx resolutionJoinFixture(long tenant) throws Exception {
        String n = String.valueOf(System.nanoTime());
        String products = "t_asr_p_" + n, lookup = "t_asr_c_" + n, entity = "AsOfRes" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("asof-res");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (" + tenant + ", 'P1', 'C1', 'n')");
        insertIdentity(adminDs, tenant, entity, products, "P1", UUID.randomUUID());
        return new ResFx(tenant, products, lookup, index, entity);
    }

    private Instant lookupIngestedAt(String lookup) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT max(_ingested_at) FROM connector." + lookup)) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    @Test
    void aResolutionLookupNeverScannedStopsTheAdvanceEvenWithNothingUncovered() throws Exception {
        ResFx f = resolutionJoinFixture(850_501);
        markStore.deleteAllFor(f.tenant(), f.index());         // applying the index queued marks: only the bound may answer
        RecordingSink sink = new RecordingSink();

        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).as("no watermark for the lookup yet").isEmpty();
        assertThat(sink.advances).isEmpty();
    }

    @Test
    void aCoveredRunDoesNotReleaseABoundAheadOfAnUnscannedLookupRow() throws Exception {
        ResFx f = resolutionJoinFixture(850_502);
        reconciler.sweep();                                    // the lookup's idle watermark: the bound now exists
        markStore.deleteAllFor(f.tenant(), f.index());
        UUID run = insertRun(adminDs, f.tenant(), "Completed", false);
        coverage.cover(f.tenant(), f.entity().toLowerCase(Locale.ROOT), List.of(run));   // the resolution pass covered it
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850502, 'C1', 'Pumps')");
        Instant row = lookupIngestedAt(f.lookup());
        RecordingSink sink = new RecordingSink();

        Instant lookupWatermark = watermark(adminDs, f.tenant(), "connector." + f.lookup());
        assertThat(lookupWatermark).as("the scan has not seen the row").isBefore(row);
        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).hasValueSatisfying(b ->
            assertThat(b).as("coverage alone cannot release it").isBeforeOrEqualTo(lookupWatermark));

        reconciler.sweep();                                    // scans the lookup: the entity's mark is now pending
        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).as("marks pending").isEmpty();

        awaitSafeBoundPast(advancer::safeBound, row);
        reconciler.sweep();                                    // the watermark can now reach the row itself (re-marks it)
        markStore.deleteAllFor(f.tenant(), f.index());         // catch-up applied them
        assertThat(watermark(adminDs, f.tenant(), "connector." + f.lookup())).as("the row is covered now")
            .isAfterOrEqualTo(row);
        assertThat(advancer.advance(f.tenant(), f.index(), sink, "gen-1")).hasValueSatisfying(b ->
            assertThat(b).as("scanned, drained and covered: the bound moves past the row").isAfterOrEqualTo(row));
    }

    /** Records only {@code advanceAsOf}; every other call is irrelevant to the advancer. */
    private static final class RecordingSink implements ProjectionSink {
        final List<Instant> advances = new CopyOnWriteArrayList<>();

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return ProviderStatus.available(); }
        @Override public String beginGeneration(long t, String i, ProjectionDefinition d) { return "gen-1"; }
        @Override public void upsert(long t, String i, String g, List<ProjectionDocument> docs) {}
        @Override public void delete(long t, String i, String g, List<String> ids) {}
        @Override public void publish(long t, String i, String g) {}
        @Override public void abandon(long t, String i, String g) {}
        @Override public void purgeIndex(long t, String i) {}
        @Override public long liveDocumentCount(long t, String i) { return 0L; }
        @Override public void advanceAsOf(long t, String i, String g, Instant reflectedUpTo) { advances.add(reflectedUpTo); }
    }
}
