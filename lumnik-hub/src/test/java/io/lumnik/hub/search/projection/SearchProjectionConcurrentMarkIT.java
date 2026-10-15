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
import org.junit.jupiter.api.RepeatedTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code @QuarkusTest} proof of the design doc §1/§5c re-mark race — spec test 13, the Review Focus
 * item this task brief calls out by name: a mark concurrently reissued a fresh {@code id} between
 * catch-up's own batch fetch and its delete-by-exact-id must not be silently lost.
 *
 * <p>The timing mechanism mirrors {@code SearchProjectionFullRebuildIT}'s own {@code
 * beforeWatermarkRead}/{@code afterWatermarkRead} precedent exactly: a package-private, no-op
 * {@code Runnable} test hook ({@link SearchProjectionWorker#afterMarkBatchFetch}) fires at one precise
 * point inside {@link SearchProjectionWorker#runCatchUp} — right after the mark batch is fetched (the
 * stale id is already in hand) but before that batch is applied and its marks deleted by those exact
 * ids. The "concurrent" re-mark is driven synchronously from inside the hook, on the SAME thread, not
 * a real second thread: the property under test is program order at a specific instant, not real
 * scheduling contention, exactly the reasoning {@code SearchProjectionFullRebuildIT}'s own javadoc
 * gives for preferring that idiom over a {@code CyclicBarrier}-style real race here.
 *
 * <p>The {@code unique}/{@code yaml}/{@code searchIndexYaml}/{@code matchEntityYaml} fixtures are
 * shared via {@link SearchProjectionItSupport}; {@code createMatchTable} stays a private copy — see
 * that class's own javadoc for why.
 */
@QuarkusTest
class SearchProjectionConcurrentMarkIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    // -- match: mode fixtures (code/name) -------------------------------------------------------

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

    private void updateMatchRowName(String table, long tenant, String code, String newName) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE connector." + table + " SET src_name = ? WHERE tenant_id = ? AND src_code = ?")) {
            ps.setString(1, newName);
            ps.setLong(2, tenant);
            ps.setString(3, code);
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

    // -- direct reads ---------------------------------------------------------------------------

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

    private long markIdFor(long tenant, String indexName, String canonicalKey) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ? "
               + "AND canonical_key = ? AND mark_kind = 'KEY'")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            ps.setString(3, canonicalKey);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // -- spec test 13: the re-mark race -----------------------------------------------------------

    /**
     * Repeated to build confidence the invariant is genuinely pinned, not order-dependent flakiness —
     * mirrors {@code SearchProjectionFullRebuildIT.reapplyRaceSubCasesAreDeterministicAcrossRepeatedRuns}'s
     * own reasoning: the hook runs synchronously on the test's own thread, so there is no real
     * scheduling nondeterminism to shake out; each repetition instead guards against a fixture/state
     * leak (a fresh {@code unique()} entity/table/index per run rules that out too).
     */
    @RepeatedTest(5)
    void concurrentRemarkDuringDrainIsNeverLostAndAppliedWholeNextTick() throws Exception {
        long tenant = 671_901_001L;
        String entityName = unique("Race1");
        String table = unique("t_race1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "k1", "Before");

        String idxName = unique("race1-");
        applySearchIndex(tenant, idxName, entityName, "name");

        RecordingSink rebuildSink = new RecordingSink();
        SearchProjectionWorker rebuildWorker = workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry);
        rebuildWorker.rebuild(tenant, idxName);
        assertThat(rebuildSink.upserted).hasSize(1);
        String canonicalKey = rebuildSink.upserted.get(0).id();

        long positionAfterRebuild = stateStore.find(tenant, idxName).orElseThrow().position();
        assertThat(markCount(tenant, idxName)).isZero();

        // What tick 1 will fetch and hold onto.
        updateMatchRowName(table, tenant, "k1", "During");
        markStore.markKeys(tenant, idxName, entityName, List.of(canonicalKey), "tick1");
        long tick1MarkId = markIdFor(tenant, idxName, canonicalKey);

        RecordingSink tick1Sink = new RecordingSink();
        SearchProjectionWorker tick1Worker = workerWith(tick1Sink, markStore, stateStore, adminDs, readonlyDs, registry);
        // The "concurrent" writer: lands strictly between tick 1's own fetch (tick1MarkId already in
        // hand) and its apply-and-delete step, updating the SAME row again and re-marking the SAME
        // canonical key — Task 3's ON CONFLICT DO UPDATE reissues it under a fresh id.
        tick1Worker.afterMarkBatchFetch = () -> {
            try {
                updateMatchRowName(table, tenant, "k1", "Concurrent");
                markStore.markKeys(tenant, idxName, entityName, List.of(canonicalKey), "concurrent-remark");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        CatchUpResult result1 = tick1Worker.catchUp(tenant, idxName);

        assertThat(result1).isNotEqualTo(CatchUpResult.REBUILD);
        // Tick 1's own apply-read runs AFTER the hook (same thread, same call) — it harmlessly reads
        // whatever is live at that moment ("Concurrent"), never the mid-flight "During". This is not
        // itself the property under test; it just documents that catch-up always reads live, never a
        // value cached at fetch time.
        assertThat(tick1Sink.upserted).hasSize(1);
        assertThat(tick1Sink.upserted.get(0).fields().get("name")).isEqualTo("Concurrent");

        // The property under test: deleting by the STALE fetched id (tick1MarkId) must match zero
        // rows once the concurrent DO UPDATE has reissued that same logical mark under a fresh id —
        // so position must not advance past it, and the mark itself must still be there afterward,
        // never silently dropped.
        Optional<SearchProjectionState> afterTick1 = stateStore.find(tenant, idxName);
        assertThat(afterTick1).isPresent();
        assertThat(afterTick1.get().position())
            .as("the stale delete-by-exact-id matched zero rows -> position stays exactly where it "
              + "was before this tick, never advanced past the still-pending mark")
            .isEqualTo(positionAfterRebuild);
        assertThat(markCount(tenant, idxName))
            .as("the concurrently-reissued mark is still there — never lost")
            .isEqualTo(1L);

        long survivingMarkId = markIdFor(tenant, idxName, canonicalKey);
        assertThat(survivingMarkId)
            .as("reissued under a FRESH id by the concurrent re-mark's own ON CONFLICT DO UPDATE")
            .isNotEqualTo(tick1MarkId);

        // Tick 2: a further, unambiguous change with no more racing — proves the reissued mark is
        // picked up WHOLE and the view's CURRENT value at that time is what finally gets applied.
        updateMatchRowName(table, tenant, "k1", "Final");

        RecordingSink tick2Sink = new RecordingSink();
        SearchProjectionWorker tick2Worker = workerWith(tick2Sink, markStore, stateStore, adminDs, readonlyDs, registry);
        CatchUpResult result2 = tick2Worker.catchUp(tenant, idxName);

        assertThat(result2).isNotEqualTo(CatchUpResult.REBUILD);
        assertThat(tick2Sink.upserted)
            .as("the next tick picks up the reissued mark and applies the CURRENT view value")
            .hasSize(1);
        assertThat(tick2Sink.upserted.get(0).fields().get("name")).isEqualTo("Final");
        assertThat(markCount(tenant, idxName)).as("fully drained this time — no more race").isZero();
        assertThat(stateStore.find(tenant, idxName).orElseThrow().position()).isEqualTo(survivingMarkId);
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
