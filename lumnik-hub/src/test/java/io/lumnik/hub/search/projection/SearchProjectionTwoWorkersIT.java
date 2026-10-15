package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.deleteSearchIndexRow;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.insertPurgeRow;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.purgeRowCount;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Spec test 16: {@link RunLock} arbitrating two racing rebuilds of the SAME {@code (tenant, index)}
 * pair, and a rebuild racing a purge for that same pair.
 *
 * <p>{@code RunLock} is session-level (design doc §1/§2 D-lock) — one Postgres advisory lock per
 * physical connection, held for as long as one worker's unit of work is in flight. The genuinely
 * concurrent property under test is narrow: {@link RunLock#tryAcquire} on the SAME key from two
 * threads at once must let exactly one through. Everything DOWNSTREAM of "did I get the lock" —
 * the loser's early return before any state write, the winner's own rebuild/catch-up/purge-drain
 * mechanics — is already exhaustively covered by Tasks 12/13/14's own unit and {@code @QuarkusTest}
 * ITs (in particular {@code SearchProjectionFullRebuildIT}, {@code SearchProjectionIncrementalCatchupIT},
 * {@code SearchProjectionPurgeDrainIT}). So only the raw lock race below uses two real threads; the
 * downstream claims ("the loser skips without error", "the winner's state survives unclobbered",
 * "a purge never lands mid-rebuild") are driven by hand, holding the SAME lock key directly on a
 * side connection to stand in for "the other worker already owns this pair" — the simpler proxy the
 * design doc explicitly permits, and the identical single-thread-drives-both-phases idiom {@code
 * SearchProjectionStaleDiscoveryIT} and {@code SearchProjectionFullRebuildIT}'s own reapply-race
 * tests already use for the same reason (a real race could only approximate an ordering this test
 * pins exactly).
 *
 * <p>The OTHER half of "rebuild racing a purge" — a purge that already finished before a rebuild
 * attempt's lock claim, so the rebuild finds nothing and never publishes over a just-purged index —
 * is already proven by {@code SearchProjectionStaleDiscoveryIT.staleDiscoveryBeforeRebuildsLockClaimFindsNothingToRebuild}.
 * That test and this class's own {@link #purgeSkipsWhenTheLockIsHeldByAnInFlightRebuildNeverPartiallyDraining}
 * together cover both directions of the race without duplicating either.
 */
@QuarkusTest
class SearchProjectionTwoWorkersIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    // -- match: mode fixtures (createMatchTable stays a private copy — see SearchProjectionItSupport's
    // own javadoc for why; unique/yaml/searchIndexYaml/matchEntityYaml are shared from there) --------

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

    private void insertMatchRow(String table, long tenant, String code, String name, String city) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_code, src_name, src_city) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, city);
            ps.executeUpdate();
        }
    }

    private void applyMatchEntity(long tenant, String name, String table) throws Exception {
        createMatchTable(table);
        yaml(tenant).body(matchEntityYaml(name, table, "city")).post("/api/entities/apply").then().statusCode(200);
    }

    private void applySearchIndex(long tenant, String name, String entity, String... fields) {
        yaml(tenant).body(searchIndexYaml(name, entity, fields)).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
    }

    // -- 1: the raw lock race — two real threads, one key, exactly one winner -------------------
    //
    // Standing in for "two rebuilds racing": RunLock.tryAcquire is plain JDBC (one PreparedStatement
    // over a pooled Connection) — no CDI request context, no JTA transaction, nothing that needs
    // Arc.container()/TenantContext propagation onto a manually-spawned thread the way Panache-backed
    // concurrency tests elsewhere (ConnectorConcurrencyCursorIT) require. A CyclicBarrier(2) guarantees
    // both threads call tryAcquire at (as close as the JVM allows to) the same instant.

    @RepeatedTest(5)
    void twoConcurrentTryAcquireCallsOnTheSameKeyRaceToExactlyOneWinner() throws Exception {
        UUID key = SearchProjectionLockKey.of(671_916_100L, unique("race-"));

        CyclicBarrier bothReady = new CyclicBarrier(2);
        AtomicReference<RunLock> slot1 = new AtomicReference<>();
        AtomicReference<RunLock> slot2 = new AtomicReference<>();
        AtomicReference<Exception> err1 = new AtomicReference<>();
        AtomicReference<Exception> err2 = new AtomicReference<>();

        Thread t1 = new Thread(() -> attempt(key, bothReady, slot1, err1), "two-workers-race-1");
        Thread t2 = new Thread(() -> attempt(key, bothReady, slot2, err2), "two-workers-race-2");
        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);

        assertThat(err1.get()).as("thread 1's own tryAcquire attempt").isNull();
        assertThat(err2.get()).as("thread 2's own tryAcquire attempt").isNull();

        // Arrays.asList, not List.of: exactly one of the two slots is legitimately null (the loser).
        List<RunLock> results = Arrays.asList(slot1.get(), slot2.get());
        long winners = results.stream().filter(Objects::nonNull).count();
        try {
            assertThat(winners)
                .as("exactly one of the two concurrent tryAcquire calls on the SAME key succeeds — "
                  + "the loser must see null, never a second lock on the identical key")
                .isEqualTo(1L);
        } finally {
            for (RunLock r : results) if (r != null) r.close();
        }
    }

    private void attempt(UUID key, CyclicBarrier barrier, AtomicReference<RunLock> slot, AtomicReference<Exception> err) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            slot.set(RunLock.tryAcquire(adminDs, key));
        } catch (Exception e) {
            err.set(e);
        }
    }

    // -- 2: two rebuilds racing — the loser skips silently, the winner's state is never clobbered --
    //
    // "Worker 1" is simulated by holding the pair's own RunLock directly on a side connection (the
    // simpler proxy the design doc permits) rather than a second real thread mid-rebuild; a real
    // SearchProjectionWorker's rebuild() attempt while that lock is held IS worker 2's own, genuine,
    // unmodified code path — only the "worker 1 is busy" precondition is hand-driven.

    @Test
    void loserRebuildSkipsSilentlyAndTheWinnersStateIsNeverClobbered() throws Exception {
        long tenant = 671_916_001L;
        String entityName = unique("Two1");
        String table = unique("t_two1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Leo", "Metz");

        String idxName = unique("two1-");
        applySearchIndex(tenant, idxName, entityName, "name");
        long expectedWatermark = markStore.maxMarkId(tenant, idxName);
        assertThat(expectedWatermark).as("the apply's own ALL mark exists").isPositive();

        UUID lockKey = SearchProjectionLockKey.of(tenant, idxName);
        RunLock heldByWorker1 = RunLock.tryAcquire(adminDs, lockKey);
        assertThat(heldByWorker1).as("setup sanity: worker 1 holds this pair's lock").isNotNull();

        // Worker 2's own, genuinely unmodified rebuild() attempt while worker 1's lock is held.
        RecordingSink loserSink = new RecordingSink("fake");
        SearchProjectionWorker loserWorker = workerWith(loserSink, markStore, stateStore, adminDs, readonlyDs, registry);
        loserWorker.rebuild(tenant, idxName);

        assertThat(loserSink.beganGenerations).as("the loser never begins a generation").isEmpty();
        assertThat(loserSink.published).as("the loser never publishes").isEmpty();
        assertThat(loserSink.abandoned).as("the loser never abandons — it never even started").isEmpty();
        assertThat(stateStore.find(tenant, idxName))
            .as("the loser's early return happens BEFORE transitionState — no state row exists yet")
            .isEmpty();

        heldByWorker1.close(); // worker 1 finishes its own unit of work and releases

        RecordingSink winnerSink = new RecordingSink("fake");
        SearchProjectionWorker winnerWorker = workerWith(winnerSink, markStore, stateStore, adminDs, readonlyDs, registry);
        winnerWorker.rebuild(tenant, idxName); // the lock is free now — this is the real winner

        assertThat(winnerSink.published).as("the winner's rebuild runs to completion").hasSize(1);
        Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo("ready");
        assertThat(state.get().liveGeneration())
            .as("live_generation ends at the single winner's own generation")
            .isEqualTo(winnerSink.published.get(0));
        assertThat(state.get().position())
            .as("position ends at the winner's own watermark — never overwritten, since the loser "
              + "never wrote anything to overwrite it with")
            .isEqualTo(expectedWatermark);
    }

    // -- 3: a purge never lands mid-rebuild — the OTHER direction of test 16's own rebuild-vs-purge
    // race (the "purge finished first, so a later rebuild finds nothing" direction is already proven
    // by SearchProjectionStaleDiscoveryIT — see this class's own javadoc). -------------------------

    @Test
    void purgeSkipsWhenTheLockIsHeldByAnInFlightRebuildNeverPartiallyDraining() throws Exception {
        long tenant = 671_916_002L;
        String entityName = unique("Two2");
        String table = unique("t_two2_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Nina", "Metz");

        String idxName = unique("two2-");
        applySearchIndex(tenant, idxName, entityName, "name");

        // Bring the pair to "ready" first — real documents/state/marks exist to protect.
        RecordingSink rebuildSink = new RecordingSink("fake");
        workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idxName);
        assertThat(rebuildSink.published).hasSize(1);

        UUID lockKey = SearchProjectionLockKey.of(tenant, idxName);
        RunLock heldByInFlightRebuild = RunLock.tryAcquire(adminDs, lockKey);
        assertThat(heldByInFlightRebuild).as("setup sanity: the in-flight rebuild holds this pair's lock").isNotNull();

        // A tenant-delete style purge lands for the SAME pair while that rebuild's lock is still held —
        // SearchIndexStore.deleteWithPurge's own row shapes, reproduced by raw SQL like the sibling ITs.
        deleteSearchIndexRow(adminDs, tenant, idxName);
        insertPurgeRow(adminDs, tenant, idxName, entityName);

        RecordingSink purgeSink = new RecordingSink("fake");
        workerWith(purgeSink, markStore, stateStore, adminDs, readonlyDs, registry).purgeDrain();

        assertThat(purgeSink.purgedTenants)
            .as("purge-drain's own tryAcquire sees the lock already held — it must skip this row, "
              + "never call sink.purgeIndex mid-rebuild")
            .isEmpty();
        assertThat(purgeRowCount(adminDs, tenant, idxName))
            .as("the purge row is left undrained, to retry on a later tick — never dropped")
            .isEqualTo(1L);
        assertThat(stateStore.find(tenant, idxName))
            .as("the state row the in-flight rebuild owns is untouched while its lock is held")
            .isPresent();

        heldByInFlightRebuild.close(); // the rebuild finishes and releases

        RecordingSink purgeSink2 = new RecordingSink("fake");
        workerWith(purgeSink2, markStore, stateStore, adminDs, readonlyDs, registry).purgeDrain();

        assertThat(purgeSink2.purgedTenants).as("the retried purge now runs to completion").contains(tenant);
        assertThat(purgeRowCount(adminDs, tenant, idxName)).as("purge row drained").isZero();
        assertThat(stateStore.find(tenant, idxName)).as("state row gone").isEmpty();
    }

    // -- fake sink --------------------------------------------------------------------------------

    /** Records every call a real rebuild/purge-drain drives a {@link ProjectionSink} through. */
    private static final class RecordingSink implements ProjectionSink {
        private final String name;
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        final List<String> published = new CopyOnWriteArrayList<>();
        final List<String> abandoned = new CopyOnWriteArrayList<>();
        final List<String> beganGenerations = new CopyOnWriteArrayList<>();
        final List<Long> purgedTenants = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        RecordingSink(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            String gen = "gen-" + (++generationCounter);
            beganGenerations.add(gen);
            return gen;
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            upserted.addAll(documents);
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            published.add(generation);
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {
            abandoned.add(generation);
        }

        @Override public void purgeIndex(long tenantId, String searchIndex) {
            purgedTenants.add(tenantId);
        }

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
