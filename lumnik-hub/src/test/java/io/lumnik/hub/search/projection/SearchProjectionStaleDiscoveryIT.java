package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * Spec test 22: discovery snapshots a {@code valid} {@code core.search_index} row for a
 * {@code (tenant, index)} pair; before THIS worker actually claims the pair's {@code RunLock},
 * another replica finishes the entire delete-then-purge-drain sequence for that exact pair first —
 * the declaration is gone, {@code sink.purgeIndex} has already been called, and both the purge row
 * and the state row are gone too (not merely "the declaration is gone": {@code
 * SearchProjectionPurgeDrainIT} already proves purge-drain's own shape; here it is the SETUP a
 * stale rebuild/catch-up must survive). This worker then proceeds with its now-stale discovery
 * result and claims the lock anyway — {@code rebuild}/{@code catchUp}'s own re-read of {@code
 * core.search_index} INSIDE the lock (Task 12/13's "never trust discovery's snapshot" discipline)
 * must see nothing and abandon cleanly: no state row is (re)created, and the sink under test here
 * receives NO call at all — not {@code beginGeneration}, not {@code upsert}, not {@code publish},
 * not {@code abandon}.
 *
 * <p>No real second thread is needed: the two phases ("the other replica finishes first" and "this
 * worker proceeds with its stale snapshot") are driven by hand, strictly in that order, on this same
 * thread — {@code SearchProjectionFullRebuildIT}'s own reapply-race tests use the identical idiom
 * for the same reason (a real race could only approximate an ordering this test pins exactly). The
 * {@code unique}/{@code yaml}/{@code searchIndexYaml}/{@code matchEntityYaml}/{@code
 * insertPurgeRow}/{@code purgeRowCount}/{@code deleteSearchIndexRow} fixtures are shared via {@link
 * SearchProjectionItSupport}; {@code createMatchTable} stays a private copy — see that class's own
 * javadoc for why.
 */
@QuarkusTest
class SearchProjectionStaleDiscoveryIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    // -- match: mode fixtures -------------------------------------------------------------------

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

    private void updateMatchRowCity(String table, long tenant, String code, String newCity) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE connector." + table + " SET src_city = ? WHERE tenant_id = ? AND src_code = ?")) {
            ps.setString(1, newCity);
            ps.setLong(2, tenant);
            ps.setString(3, code);
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

    // -- "another replica already finished this exact sequence" ---------------------------------

    private long markCount(long tenant, String indexName) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Runs a real {@link SearchProjectionWorker#purgeDrain()} against a throwaway sink, standing
     *  in for "another replica" that already fully drained this exact {@code (tenant, index)} pair —
     *  the real, already-committed Task 14 code path, not a hand-rolled imitation of it. */
    private void anotherReplicaFullyDrainsThePurge(long tenant, String name, String entity) throws Exception {
        deleteSearchIndexRow(adminDs, tenant, name);
        insertPurgeRow(adminDs, tenant, name, entity);
        RecordingSink replicaSink = new RecordingSink();
        workerWith(replicaSink, markStore, stateStore, adminDs, readonlyDs, registry).purgeDrain();
        assertThat(replicaSink.purgedTenants)
            .as("setup sanity: the OTHER replica's purge actually ran for this exact pair")
            .contains(tenant);
        assertThat(purgeRowCount(adminDs, tenant, name)).as("setup sanity: purge row drained").isZero();
        assertThat(stateStore.find(tenant, name)).as("setup sanity: state row gone").isEmpty();
        assertThat(markCount(tenant, name)).as("setup sanity: every mark gone").isZero();
    }

    // -- 1: rebuild sub-case ----------------------------------------------------------------------

    @Test
    void staleDiscoveryBeforeRebuildsLockClaimFindsNothingToRebuild() throws Exception {
        long tenant = 671_922_001L;
        String entityName = unique("Stale1");
        String table = unique("t_stale1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Zoe", "Metz");

        String idxName = unique("stale1-");
        applySearchIndex(tenant, idxName, entityName, "name");
        // Discovery's own snapshot at this instant: no state row yet -> "needs a full rebuild",
        // (tenant, idxName, entityName) is exactly the tuple dispatch() would pass to rebuild().

        // Another replica finishes the ENTIRE delete-then-purge-drain sequence for this same pair
        // before this worker gets around to actually claiming the RunLock.
        anotherReplicaFullyDrainsThePurge(tenant, idxName, entityName);

        // This worker now proceeds with its stale discovery result and claims the lock anyway.
        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName);

        assertThat(sink.beganGenerations).as("no beginGeneration call").isEmpty();
        assertThat(sink.upserted).as("no upsert call").isEmpty();
        assertThat(sink.published).as("no publish call").isEmpty();
        assertThat(sink.abandoned).as("no abandon call").isEmpty();
        assertThat(stateStore.find(tenant, idxName)).as("no state row (re)created").isEmpty();
    }

    // -- 2: catch-up sub-case -----------------------------------------------------------------------

    @Test
    void staleDiscoveryBeforeCatchUpsLockClaimFindsNothingToCatchUp() throws Exception {
        long tenant = 671_922_002L;
        String entityName = unique("Stale2");
        String table = unique("t_stale2_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Yara", "Metz");

        String idxName = unique("stale2-");
        applySearchIndex(tenant, idxName, entityName, "name");

        // Bring the pair to "ready" first, then mutate + re-mark so discovery would classify it
        // "position behind head" -> catchUp(), exactly the case this sub-case restates.
        RecordingSink rebuildSink = new RecordingSink();
        workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idxName);
        assertThat(rebuildSink.published).hasSize(1);
        String keyId = rebuildSink.upserted.get(0).id();

        updateMatchRowCity(table, tenant, "c1", "Nancy");
        markStore.markKeys(tenant, idxName, entityName, List.of(keyId), "stale-discovery-test");
        // Discovery's own snapshot at this instant: position < head -> catchUp(), the tuple
        // (tenant, idxName, entityName) is exactly what dispatch() would pass to catchUp().

        // Another replica finishes the ENTIRE delete-then-purge-drain sequence for this same pair
        // — including the ready state row and the fresh KEY mark just above — before this worker
        // gets around to actually claiming the RunLock.
        anotherReplicaFullyDrainsThePurge(tenant, idxName, entityName);

        // This worker now proceeds with its stale discovery result and claims the lock anyway.
        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        CatchUpResult result = worker.catchUp(tenant, idxName);

        assertThat(result)
            .as("abandons cleanly, never signalling a rebuild is still owed")
            .isNotEqualTo(CatchUpResult.REBUILD);
        assertThat(sink.upserted).as("no upsert call").isEmpty();
        assertThat(sink.deleted).as("no delete call").isEmpty();
        assertThat(sink.beganGenerations).as("catch-up never begins a generation anyway").isEmpty();
        assertThat(stateStore.find(tenant, idxName)).as("no state row (re)created").isEmpty();
    }

    // -- fake sink ----------------------------------------------------------------------------------

    /** Records every call a real rebuild/catch-up/purge-drain drives a {@link ProjectionSink}
     *  through — the "no call at all" oracle both tests above check. */
    private static final class RecordingSink implements ProjectionSink {
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        final List<List<String>> deleted = new CopyOnWriteArrayList<>();
        final List<String> published = new CopyOnWriteArrayList<>();
        final List<String> abandoned = new CopyOnWriteArrayList<>();
        final List<String> beganGenerations = new CopyOnWriteArrayList<>();
        final List<Long> purgedTenants = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            String gen = "gen-" + (++generationCounter);
            beganGenerations.add(gen);
            return gen;
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            upserted.addAll(documents);
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {
            deleted.add(List.copyOf(ids));
        }

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
