package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.search.SearchIndexManifest;
import io.lumnik.hub.search.SearchIndexManifestParser;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * {@code @QuarkusTest} coverage of {@link SearchProjectionWorker#triggerRebuild} — Task 4's own
 * on-demand claim door. Spec labels tests 1-2 "unit-level, no database", but {@code
 * RunLock.tryAcquire} genuinely opens a Postgres connection and cannot be faked without a deeper,
 * out-of-scope refactor of the widely-shared {@code RunLock} class, so this IT instead uses {@code
 * SearchProjectionTwoWorkersIT}'s own established idiom: hold the same advisory-lock key on a side
 * connection to stand in for "another owner".
 */
@QuarkusTest
class SearchProjectionTriggerRebuildIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;
    @Inject EntityRegistry registry;

    @Test
    void returnsRebuildAlreadyRunningWhenTheSamePairHasAnActiveRun() throws Exception {
        long tenant = 900L;
        String index = unique("idx");
        UUID runningRunId = UUID.randomUUID();
        stateStore.insertBuilding(tenant, index, runningRunId, Instant.now());

        UUID lockKey = SearchProjectionLockKey.of(tenant, index);
        try (RunLock heldByAnotherWorker = RunLock.tryAcquire(adminDs, lockKey)) {
            assertThat(heldByAnotherWorker).isNotNull();
            SearchProjectionWorker worker = workerWith(mock(ProjectionSink.class), markStore, stateStore,
                adminDs, readonlyDs, registry);
            var snapshot = new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", null);
            RebuildClaim claim = worker.triggerRebuild(tenant, index, snapshot);
            assertThat(claim).isInstanceOf(RebuildClaim.RebuildAlreadyRunning.class);
            assertThat(((RebuildClaim.RebuildAlreadyRunning) claim).runId()).isEqualTo(runningRunId);
        }
    }

    @Test
    void returnsPairBusyWhenTheLockIsHeldWithNoActiveRun() throws Exception {
        long tenant = 901L;
        String index = unique("idx");
        // No state row at all — the lock is held by catch-up/purge-drain, not a rebuild.
        UUID lockKey = SearchProjectionLockKey.of(tenant, index);
        try (RunLock heldByCatchup = RunLock.tryAcquire(adminDs, lockKey)) {
            assertThat(heldByCatchup).isNotNull();
            SearchProjectionWorker worker = workerWith(mock(ProjectionSink.class), markStore, stateStore,
                adminDs, readonlyDs, registry);
            var snapshot = new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", null);
            RebuildClaim claim = worker.triggerRebuild(tenant, index, snapshot);
            assertThat(claim).isInstanceOf(RebuildClaim.PairBusy.class);
        }
    }

    @Test
    void returnsDeclarationChangedWhenTheReReadDeclarationDiffersFromTheAuthorizedSnapshot() throws Exception {
        long tenant = 902L;
        String index = unique("idx");
        // Apply a valid SearchIndex over a real match: entity/table so readDeclaration() sees status
        // = 'valid' with a real manifest, then hand triggerRebuild an authorizedSnapshot naming a
        // DIFFERENT entity — a re-apply between the REST handler's own check and this call.
        String table = "widget_" + unique("t");
        createMatchTable(table);
        yaml(tenant).body(matchEntityYaml("widget", table)).post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(searchIndexYaml(index, "widget", "name")).post("/api/search-indexes/apply").then().statusCode(200);

        SearchProjectionWorker worker = workerWith(mock(ProjectionSink.class), markStore, stateStore,
            adminDs, readonlyDs, registry);
        var staleSnapshot = new SearchProjectionWorker.SearchIndexDeclaration("valid", "a-different-entity", null);
        RebuildClaim claim = worker.triggerRebuild(tenant, index, staleSnapshot);
        assertThat(claim).isInstanceOf(RebuildClaim.DeclarationChanged.class);
        assertThat(stateStore.find(tenant, index)).isEmpty(); // no active_run_id was ever written
    }

    /**
     * #703 finding: {@link SearchProjectionWorker#triggerRebuild}'s own concurrency gate releases the
     * permit it took, once its async run reaches a terminal outcome. {@code worker.initExecutor()} is
     * called directly — the exact {@code @PostConstruct} a CDI-managed instance runs on its own — so
     * the run genuinely proceeds on a real virtual thread, exactly like production; {@code await()}
     * polls rather than assuming any particular timing, since the run itself is asynchronous.
     */
    @Test
    void aSuccessfulOnDemandRebuildReleasesItsPermitOnceItReachesATerminalOutcome() throws Exception {
        long tenant = 903L;
        String index = unique("idx");
        String table = "widget_" + unique("t");
        createMatchTable(table);
        yaml(tenant).body(matchEntityYaml("widget", table)).post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(searchIndexYaml(index, "widget", "name")).post("/api/search-indexes/apply").then().statusCode(200);

        // The exact declaration triggerRebuild's own readDeclaration() will independently re-read —
        // same SQL/parser pairing, so this "authorized snapshot" genuinely matches and the run
        // proceeds past the DeclarationChanged check into a real Started/terminal flow.
        String manifestYaml = Jdbc.queryOne(adminDs, "test.readManifestYaml",
                "SELECT manifest_yaml FROM core.search_index WHERE tenant_id = ? AND name = ?",
                ps -> { ps.setLong(1, tenant); ps.setString(2, index); },
                rs -> rs.getString(1))
            .orElseThrow();
        SearchIndexManifest manifest = new SearchIndexManifestParser().parse(manifestYaml);
        var snapshot = new SearchProjectionWorker.SearchIndexDeclaration("valid", "widget", manifest);

        SearchProjectionWorker worker = workerWith(mock(ProjectionSink.class), markStore, stateStore,
            adminDs, readonlyDs, registry);
        worker.maxConcurrentRebuilds = 3;
        worker.initExecutor(); // real rebuildExecutor + a full rebuildPermits(3), same as @PostConstruct

        RebuildClaim claim = worker.triggerRebuild(tenant, index, snapshot);

        assertThat(claim).isInstanceOf(RebuildClaim.Started.class);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(worker.rebuildPermits.availablePermits()).isEqualTo(3));
        // The permit release proven above genuinely came from THIS run reaching a terminal outcome,
        // not from some unrelated path.
        assertThat(stateStore.find(tenant, index)).isPresent();
        assertThat(stateStore.find(tenant, index).get().status()).isIn("ready", "degraded", "cancelled");
    }

    /** {@code SearchProjectionFullRebuildIT}'s own guarded-DDL idiom, copied rather than shared —
     *  {@code SearchProjectionItSupport}'s own javadoc explains why {@code createMatchTable} is
     *  deliberately NOT extracted there. */
    private void createMatchTable(String table) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector." + table);
            st.execute("CREATE TABLE connector." + table + " (tenant_id bigint NOT NULL, "
                + "src_code text, src_name text, src_city text)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
            st.execute("DO $$ BEGIN GRANT SELECT ON connector." + table + " TO lumnik_readonly;"
                + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        }
    }
}
