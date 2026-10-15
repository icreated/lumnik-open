package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code @QuarkusTest} proof of {@link SearchProjectionRunReaper} (design doc D-reaper, §2) — the
 * {@code RunReaper}/{@code ExportRunReaper} shape applied to {@code core.search_projection_state}.
 * Spec tests 11 (the four run-tracking branch cases below — a legacy pre-V58 row with no run
 * tracking at all, a matching cancel request, a cancel request against a resolved provider that
 * does not match the run's own, and no cancellation — plus the publishing branch), 17 (healed + a
 * genuinely fresh rebuild, and "locked -> left alone") and 23 (the SELECT/tryAcquire race window).
 *
 * <p>{@link SearchProjectionRunReaper} is constructed directly with {@code new}, its package-private
 * {@code adminDs}/{@code states} fields set by hand afterward — the same idiom {@code
 * SearchProjectionFullRebuildIT}/{@code SearchProjectionConcurrentMarkIT} use for {@link
 * SearchProjectionWorker}, so a test's mutation of the race-window hook ({@link
 * SearchProjectionRunReaper#beforeTryAcquire}) never leaks onto the CDI singleton other tests share.
 */
@QuarkusTest
class SearchProjectionRunReaperIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    /** Named "postgres" so branch 1's own currently-resolved-provider match (spec test 11, case 2) has
     *  something to match against; the case-3 test deliberately records a DIFFERENT provider name to
     *  prove the mismatch path never abandons. Stubbing {@code name()} unconditionally (rather than
     *  only in the tests that need a match) also keeps every other test's own unstubbed call from
     *  NPEing inside {@link SearchProjectionRunReaper#reconcile} — branch 1 always calls it once it
     *  has resolved a sink, whether or not the name ends up matching. */
    private final ProjectionSink fakeSink = namedFake("postgres");

    private static ProjectionSink namedFake(String name) {
        ProjectionSink sink = mock(ProjectionSink.class);
        when(sink.name()).thenReturn(name);
        return sink;
    }

    private SearchProjectionRunReaper newReaper() {
        SearchProjectionRunReaper reaper = new SearchProjectionRunReaper();
        reaper.adminDs = adminDs;
        reaper.states = stateStore;
        reaper.sinkGate = new ProjectionSinkGate() {
            @Override public Optional<ProjectionSink> resolve() { return Optional.of(fakeSink); }
        };
        reaper.metrics = new SearchProjectionMetrics();
        reaper.metrics.registry = new SimpleMeterRegistry();
        return reaper;
    }

    // -- match: mode fixtures (code/name), same shape as SearchProjectionFullRebuildIT ----------

    private void createMatchTable(String table) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector." + table);
            st.execute("CREATE TABLE connector." + table + " (tenant_id bigint NOT NULL, "
                + "src_code text, src_name text)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
            st.execute("DO $$ BEGIN GRANT SELECT ON connector." + table + " TO lumnik_readonly;"
                + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        }
    }

    private void insertMatchRow(String table, long tenant, String code, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_code, src_name) VALUES (?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void applyMatchEntity(long tenant, String name, String table) throws Exception {
        createMatchTable(table);
        yaml(tenant).body(matchEntityYaml(name, table)).post("/api/entities/apply").then().statusCode(200);
    }

    private void applySearchIndex(long tenant, String name, String entity, String... fields) {
        yaml(tenant).body(searchIndexYaml(name, entity, fields)).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
    }

    // -- spec test 17, case 1: no live lock -> healed, and the very next tick is a fresh rebuild ---

    @Test
    void firstAttemptStuckBuildingIsHealedAndTheNextDiscoveryTickRunsAFreshRebuild() throws Exception {
        long tenant = 671_801_001L;
        String entityName = unique("Reap1");
        String table = unique("t_reap1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Leo");

        String idxName = unique("reap1-");
        applySearchIndex(tenant, idxName, entityName, "name");

        // Simulate a FIRST attempt stuck mid-flight: a 'building' row with live_generation still
        // NULL, seeded directly (as SearchProjectionWorker.transitionState would on a first rebuild)
        // — and crucially, nobody holds this pair's RunLock: the process that started it is gone.
        stateStore.insertBuilding(tenant, idxName, UUID.randomUUID(), Instant.now());

        SearchProjectionRunReaper reaper = newReaper();
        int reaped = reaper.reap();

        assertThat(reaped).as("this row, with no live lock, is a zombie").isGreaterThanOrEqualTo(1);
        Optional<SearchProjectionState> healed = stateStore.find(tenant, idxName);
        assertThat(healed).isPresent();
        assertThat(healed.get().status()).isEqualTo("degraded");
        assertThat(healed.get().lastError()).as("a reaped-style reason").startsWith("reaped:");
        assertThat(healed.get().retryCount()).isEqualTo(1);
        assertThat(healed.get().liveGeneration()).isNull();

        // The lock is genuinely free afterward — the reaper released it before returning.
        UUID lockKey = SearchProjectionLockKey.of(tenant, idxName);
        try (RunLock lock = RunLock.tryAcquire(adminDs, lockKey)) {
            assertThat(lock).as("the reaper must not leak the lock it took to check").isNotNull();
        }

        // The very next discovery tick treats this exactly like any other live_generation IS NULL
        // row (spec test 10's own dispatch rule) — an entirely ordinary, brand-new rebuild, never a
        // resumption: a fresh beginGeneration/publish and every row scanned again from scratch.
        //
        // discoveryTick() is deliberately CROSS-TENANT (it dispatches every tenant with at least one
        // valid SearchIndex, not just this one) and this suite's fake sink is shared for the whole
        // call, so a leftover valid SearchIndex from a wholly unrelated test sharing the same
        // DevServices Postgres can add its own, unrelated dispatch to the same sink. The assertions
        // below are therefore scoped to THIS pair's own outcome — never a bare count of every
        // publish/upsert the call happened to make — which is the only thing spec test 17 actually
        // claims.
        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.discoveryTick();

        Optional<SearchProjectionState> after = stateStore.find(tenant, idxName);
        assertThat(after).isPresent();
        assertThat(after.get().status()).isEqualTo("ready");
        assertThat(after.get().lastError()).isNull();
        assertThat(after.get().liveGeneration())
            .as("a fresh generation was begun and published for THIS pair")
            .isIn(sink.published);
        assertThat(sink.upserted)
            .as("this pair's own row was scanned again from scratch, not resumed")
            .anySatisfy(d -> assertThat(d.fields().get("name")).isEqualTo("Leo"));
    }

    // -- spec test 17, case 2: a live lock -> left completely untouched -----------------------------

    @Test
    void aBuildingRowWhoseLockIsHeldIsLeftUntouched() throws Exception {
        long tenant = 671_802_001L;
        String idxName = unique("reap2-");
        // No entity/SearchIndex needed here — the reaper only reads core.search_projection_state.
        stateStore.insertBuilding(tenant, idxName, UUID.randomUUID(), Instant.now());

        UUID lockKey = SearchProjectionLockKey.of(tenant, idxName);
        try (RunLock live = RunLock.tryAcquire(adminDs, lockKey)) {
            assertThat(live).as("the test must hold the lock, standing in for a live worker").isNotNull();

            SearchProjectionRunReaper reaper = newReaper();
            reaper.reap();

            Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
            assertThat(state).isPresent();
            assertThat(state.get().status())
                .as("a live rebuild holds the lock -> not a zombie, exactly RunReaper's own precedent")
                .isEqualTo("building");
            assertThat(state.get().lastError()).isNull();
            assertThat(state.get().retryCount()).isZero();
        }

        // Never leave a 'building' row behind for a later test's own findBuilding() scan to trip over.
        stateStore.delete(tenant, idxName);
    }

    // -- spec test 23: the SELECT/tryAcquire race window ---------------------------------------------

    /**
     * A "worker" holds the lock when the reaper's own {@code findBuilding()} SELECT sees this row as
     * {@code building}; it then finishes — releasing the lock and publishing {@code ready} — strictly
     * BETWEEN that SELECT and the reaper's own {@code tryAcquire} for this same key. {@link
     * SearchProjectionRunReaper#beforeTryAcquire} is the injectable pause point that lands the
     * "worker's" finish at exactly that instant, synchronously on this same thread — the same idiom
     * {@code SearchProjectionFullRebuildIT.beforeWatermarkRead}/{@code
     * SearchProjectionConcurrentMarkIT.afterMarkBatchFetch} already establish, preferred here over a
     * real thread race for the same reason their own javadoc gives: the property under test is
     * program order at one precise instant, not real scheduling contention.
     *
     * <p>{@code tryAcquire} must then succeed (the lock genuinely is free), but the reaper's own
     * conditional {@code UPDATE ... WHERE status = 'building'} must match zero rows — the row already
     * left {@code building} for {@code ready} by the time that UPDATE runs — so the finished rebuild
     * is never downgraded.
     */
    @Test
    void raceWindowBetweenSelectAndTryAcquireNeverDowngradesAFinishedRebuild() throws Exception {
        long tenant = 671_803_001L;
        String idxName = unique("reap3-");
        UUID runId = UUID.randomUUID();
        stateStore.insertBuilding(tenant, idxName, runId, Instant.now());

        UUID lockKey = SearchProjectionLockKey.of(tenant, idxName);
        RunLock workerLock = RunLock.tryAcquire(adminDs, lockKey);
        assertThat(workerLock).as("the 'worker' takes the lock first, standing in for a live rebuild").isNotNull();

        SearchProjectionRunReaper reaper = newReaper();
        reaper.beforeTryAcquire = () -> {
            // The "worker" finishes: releases its lock and ends the run — after the reaper's own
            // SELECT already read this row as 'building', but before the reaper's own tryAcquire.
            // Task 7 moved the reaper's own conditional UPDATE from a plain status='building' check
            // to active_run_phase/active_run_id (design doc §3.4), so the fixture standing in for "a
            // live rebuild finished" must do what runBuild's own commit-stage markReady(Connection,
            // ...) genuinely does (clear active_run_id/active_run_phase, not just flip status) —
            // the plain no-Connection markReady never touches those V58 columns and would leave this
            // row looking, to the new conditional UPDATE, exactly like it was still building.
            workerLock.close();
            try (Connection c = adminDs.getConnection()) {
                stateStore.markReady(c, tenant, idxName, "fake", "gen-1", 0L, runId);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };

        // reap()'s own returned count is deliberately not asserted here: it sums every (tenant, index)
        // pair findBuilding() sees this call, cross-tenant, and this suite shares its DevServices
        // Postgres with every other IT — a genuinely abandoned row left by an unrelated test could add
        // to it without affecting THIS pair at all. The invariant spec test 23 actually claims is fully
        // proven below, at the level of this one row.
        reaper.reap();

        Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
        assertThat(state).isPresent();
        assertThat(state.get().status())
            .as("never downgraded — the finish that landed in the exact race window sticks")
            .isEqualTo("ready");
        assertThat(state.get().liveGeneration()).isEqualTo("gen-1");
        assertThat(state.get().lastError()).isNull();
        assertThat(state.get().retryCount())
            .as("reapBuilding's conditional UPDATE never touched this row — retry_count "
              + "stayed at markReady's own reset value")
            .isZero();
    }

    // -- spec test 11: the four reconciliation branches ----------------------------------------------

    @Test
    void legacyRowWithNoActiveRunPhaseIsDegradedWithNoRunTrackingWritten() throws Exception {
        // Branch 0: a row shaped exactly like a pre-V58 write (status='building', every V58 column NULL) —
        // insertBuilding always sets active_run_phase now, so this row can only be produced by raw SQL,
        // standing in for one written by an old worker mid-rolling-deployment.
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_projection_state (tenant_id, search_index, status) VALUES (?, ?, 'building')")) {
            ps.setLong(1, 910L);
            ps.setString(2, "legacy-idx");
            ps.executeUpdate();
        }
        SearchProjectionRunReaper reaper = newReaper();
        reaper.reap();
        SearchProjectionState s = stateStore.find(910L, "legacy-idx").get();
        assertThat(s.status()).isEqualTo("degraded");
        assertThat(s.retryCount()).isEqualTo(1);
        assertThat(s.lastRebuildOutcome()).isNull(); // nothing to attribute
        verify(fakeSink, never()).abandon(anyLong(), anyString(), anyString());
    }

    @Test
    void buildingWithMatchingCancelRequestAbandonsOnTheCurrentlyResolvedProviderAndRecordsCancelled() {
        UUID runId = UUID.randomUUID();
        stateStore.insertBuilding(911L, "idx", runId, Instant.now());
        stateStore.recordActiveGeneration(911L, "idx", "gen-1", "postgres");
        stateStore.requestCancel(911L, "idx");
        SearchProjectionRunReaper reaper = newReaper();
        reaper.reap(); // nobody holds this pair's RunLock in this test process, so it reconciles immediately
        verify(fakeSink).abandon(911L, "idx", "gen-1");
        assertThat(stateStore.find(911L, "idx").get().status()).isEqualTo("cancelled");
        assertThat(stateStore.find(911L, "idx").get().lastRebuildOutcome()).isEqualTo("cancelled");
    }

    @Test
    void buildingWithMatchingCancelRequestButADifferentResolvedProviderNeverAbandonsButStillRecordsCancelled() {
        UUID runId = UUID.randomUUID();
        stateStore.insertBuilding(912L, "idx", runId, Instant.now());
        stateStore.recordActiveGeneration(912L, "idx", "gen-1", "elasticsearch"); // NOT the fake sink's own name
        stateStore.requestCancel(912L, "idx");
        SearchProjectionRunReaper reaper = newReaper();
        reaper.reap();
        verify(fakeSink, never()).abandon(anyLong(), anyString(), anyString());
        assertThat(stateStore.find(912L, "idx").get().status()).isEqualTo("cancelled");
    }

    @Test
    void buildingWithNoCancellationIsDegradedNeverAbandonedExactlyLikeToday() {
        UUID runId = UUID.randomUUID();
        stateStore.insertBuilding(913L, "idx", runId, Instant.now());
        stateStore.recordActiveGeneration(913L, "idx", "gen-1", "postgres");
        SearchProjectionRunReaper reaper = newReaper();
        reaper.reap();
        verify(fakeSink, never()).abandon(anyLong(), anyString(), anyString());
        assertThat(stateStore.find(913L, "idx").get().status()).isEqualTo("degraded");
        assertThat(stateStore.find(913L, "idx").get().lastRebuildOutcome()).isEqualTo("failed");
    }

    @Test
    void publishingIsNeverAbandonedAndForcesAFreshRebuildNextTick() {
        UUID runId = UUID.randomUUID();
        stateStore.insertBuilding(914L, "idx", runId, Instant.now());
        stateStore.casToPublishing(914L, "idx", runId);
        SearchProjectionRunReaper reaper = newReaper();
        reaper.reap();
        verify(fakeSink, never()).abandon(anyLong(), anyString(), anyString());
        SearchProjectionState s = stateStore.find(914L, "idx").get();
        assertThat(s.status()).isEqualTo("degraded");
        assertThat(s.liveGeneration()).isNull(); // next discoveryTick's own liveGeneration()==null check fires
    }

    // -- fake sink --------------------------------------------------------------------------------

    private static final class RecordingSink implements ProjectionSink {
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        final List<String> published = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            return "gen-" + (++generationCounter);
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            upserted.addAll(documents);
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            published.add(generation);
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {}

        @Override public void purgeIndex(long tenantId, String searchIndex) {}

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
