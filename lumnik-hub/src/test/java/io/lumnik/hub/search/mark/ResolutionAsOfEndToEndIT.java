package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.entity.resolution.EntityResolutionPass;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore.UncoveredBound;
import io.lumnik.hub.search.projection.SearchProjectionAsOfAdvancer;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyResolutionEntity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.awaitSafeBoundPast;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRun;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.runFence;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** #748: a resolution: entity's asOf against the real pass, the real coverage ledger and a real database. */
@QuarkusTest
class ResolutionAsOfEndToEndIT {

    @Inject EntityResolutionPass pass;
    @Inject SearchProjectionAsOfAdvancer advancer;
    @Inject SearchProjectionMarkStore markStore;
    @Inject ResolutionCoverageStore coverage;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private record Fx(long tenant, String entity, String table, String index) {
        String entityType() { return entity.toLowerCase(Locale.ROOT); }
    }

    private Fx fixture(long tenant, String label) throws Exception {
        String entity = "AsOfE2e" + label + System.nanoTime();
        String table = "t_asof_e2e_" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, entity, table);
        String index = unique("asof-e2e-" + label);
        yaml(tenant).body(searchIndex(index, entity, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);
        return new Fx(tenant, entity, table, index);
    }

    private void insertResolutionRow(Fx fx, UUID run, String customerId, String name) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO connector." + fx.table() + " (tenant_id, _run_id, \"CUSTOMER_ID\", \"NAME_COL\") VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, fx.tenant());
            ps.setObject(2, run);
            ps.setString(3, customerId);
            ps.setString(4, name);
            ps.executeUpdate();
        }
    }

    /**
     * {@code runIncremental} fans out over every resolution manifest in the JVM-wide registry, so a sibling
     * test's entity (its table dropped, say) may throw. Coverage is per entity — which is precisely why THIS
     * entity's coverage must not depend on it — and every test asserts its own entity's coverage afterwards,
     * so a failure of the entity under test still shows.
     */
    private void runPass(long tenant, UUID run) {
        try { pass.runIncremental(tenant, run); } catch (RuntimeException siblingFailure) { /* asserted via coverage */ }
    }

    /** One advancer tick with an empty queue (the queue is not what these tests are about). */
    private Optional<Instant> advanceOnce(Fx fx) {
        markStore.deleteAllFor(fx.tenant(), fx.index());
        return advancer.advance(fx.tenant(), fx.index(), mock(ProjectionSink.class), "gen-1");
    }

    @Test
    void aContentChangeHoldsAsOfUntilItsPassCoversTheRun() throws Exception {
        Fx fx = fixture(748_501, "chg");
        UUID r0 = insertRun(adminDs, fx.tenant(), "Completed", false);
        insertResolutionRow(fx, r0, "X1", "Old Name");
        runPass(fx.tenant(), r0);
        assertThat(coverage.uncoveredBound(fx.tenant(), fx.entityType())).isEqualTo(UncoveredBound.NONE);

        UUID r1 = insertRun(adminDs, fx.tenant(), "Completed", false);
        Instant f1 = runFence(adminDs, r1);
        insertResolutionRow(fx, r1, "X1", "New Name");   // content change: a new physical row, same identity

        // the view already shows the new content, no mark exists yet, the queue is empty — and asOf must not pass f1
        assertThat(advanceOnce(fx)).hasValueSatisfying(b -> assertThat(b).isBeforeOrEqualTo(f1));

        runPass(fx.tenant(), r1);
        assertThat(coverage.uncoveredBound(fx.tenant(), fx.entityType())).isEqualTo(UncoveredBound.NONE);
        awaitSafeBoundPast(advancer::safeBound, f1);     // safeBound keeps a 1 s margin behind a fresh fence
        assertThat(advanceOnce(fx)).hasValueSatisfying(b -> assertThat(b).isAfter(f1));
    }

    @Test
    void aLostEventLeavesTheRunBlockingEvenOnceTheClockHasMovedOn() throws Exception {
        Fx fx = fixture(748_502, "lost");
        UUID r1 = insertRun(adminDs, fx.tenant(), "Completed", false);
        Instant f1 = runFence(adminDs, r1);
        insertResolutionRow(fx, r1, "X1", "Name");        // no pass ever runs for r1

        awaitSafeBoundPast(advancer::safeBound, f1);      // safeBound alone would now pass f1 ...
        assertThat(advanceOnce(fx)).hasValueSatisfying(b -> assertThat(b).isBeforeOrEqualTo(f1)); // ... the fence holds it
    }

    @Test
    void aReplayReleasesAFailedRunAndAHistoricalNullFenceRun() throws Exception {
        Fx fx = fixture(748_503, "replay");
        insertRun(adminDs, fx.tenant(), "Failed", false);
        insertRun(adminDs, fx.tenant(), "Completed", true);  // predates V60: unknown bound

        assertThat(advanceOnce(fx)).isEmpty();               // unknown bound: no advance at all

        pass.runReplay(fx.tenant(), fx.entity());

        assertThat(coverage.uncoveredBound(fx.tenant(), fx.entityType())).isEqualTo(UncoveredBound.NONE);
        assertThat(advanceOnce(fx)).isPresent();
    }

    @Test
    void aRunStillActiveWhenTheReplayStartsStaysBlocking() throws Exception {
        Fx fx = fixture(748_504, "active");
        UUID active = insertRun(adminDs, fx.tenant(), "Running", false);

        pass.runReplay(fx.tenant(), fx.entity());

        UncoveredBound bound = coverage.uncoveredBound(fx.tenant(), fx.entityType());
        assertThat(bound.unknown()).isFalse();
        assertThat(bound.earliestFence()).isEqualTo(runFence(adminDs, active));
    }
}
