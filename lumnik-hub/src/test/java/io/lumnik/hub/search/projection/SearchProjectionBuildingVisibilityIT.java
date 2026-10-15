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
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code @QuarkusTest} proof of spec test 19: a full rebuild's {@code building} transition ({@code
 * SearchProjectionWorker.transitionState}, Task 12 §5b step 2) is an ordinary, immediately-committed
 * transaction — independent of the long-held scan transaction and of the RunLock's own connection —
 * so a SECOND, wholly separate connection sees {@code status = 'building'} in {@code
 * core.search_projection_state} while the rebuild's scan is genuinely still mid-flight, not held
 * back until the whole rebuild finishes.
 *
 * <p>Proven with a slow fake {@link ProjectionSink} whose {@code upsert} blocks on a latch the test
 * releases by hand — no test hook added to production code, since {@code upsert} already blocks the
 * calling thread for as long as the sink chooses to, which is all this needs. The rebuild itself runs
 * on a background thread (a plain {@link ExecutorService}, not the test thread) so the test thread is
 * free to open its own, independent connection and query while the rebuild's own scan connection sits
 * blocked inside {@code upsert}, transaction still open.
 */
@QuarkusTest
class SearchProjectionBuildingVisibilityIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

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

    /** Batch-inserts {@code count} rows so the full-rebuild scan (500-row batches,
     *  {@code SearchProjectionWorker.SCAN_BATCH_SIZE}) has more than one batch to drain — the first
     *  {@code upsert} call blocks while rows still remain unread, a genuinely mid-scan moment rather
     *  than only the tail after the cursor is already exhausted. */
    private void insertMatchRows(String table, long tenant, int count) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_code, src_name) VALUES (?, ?, ?)")) {
            for (int i = 0; i < count; i++) {
                ps.setLong(1, tenant);
                ps.setString(2, "c" + i);
                ps.setString(3, "Name" + i);
                ps.addBatch();
            }
            ps.executeBatch();
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

    private String stateStatus(long tenant, String indexName) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT status FROM core.search_projection_state WHERE tenant_id = ? AND search_index = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the state row must already exist").isTrue();
                return rs.getString(1);
            }
        }
    }

    // -- spec test 19 ---------------------------------------------------------------------------

    @Test
    @Timeout(60)
    void buildingStatusIsVisibleOnASecondConnectionWhileTheScanIsStillMidFlight() throws Exception {
        long tenant = 671_901_001L;
        String entityName = unique("Vis1");
        String table = unique("t_vis1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRows(table, tenant, 600); // > one 500-row scan batch

        String idxName = unique("vis1-");
        applySearchIndex(tenant, idxName, entityName, "name");

        SlowSink sink = new SlowSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> rebuildDone = pool.submit(() -> worker.rebuild(tenant, idxName));

            assertThat(sink.upsertStarted.await(30, TimeUnit.SECONDS))
                .as("the scan must reach its first upsert call within the timeout")
                .isTrue();

            // A SECOND, wholly independent admin connection — never the one the blocked scan holds.
            assertThat(stateStatus(tenant, idxName))
                .as("the building transition committed on its own, well before the scan (still "
                  + "blocked in upsert, with rows left unread) has any chance to finish")
                .isEqualTo("building");

            sink.release.countDown();
            rebuildDone.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(sink.published).as("the rebuild ran to completion once released").hasSize(1);
        Optional<SearchProjectionState> finalState = stateStore.find(tenant, idxName);
        assertThat(finalState).isPresent();
        assertThat(finalState.get().status()).isEqualTo("ready");
    }

    // -- slow fake sink ---------------------------------------------------------------------------

    /** Blocks its FIRST {@code upsert} call until the test releases it; every later call (a rebuild's
     *  own second batch, if any) returns immediately since the latch is already open by then. */
    private static final class SlowSink implements ProjectionSink {
        final CountDownLatch upsertStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> published = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        @Override public String name() { return "slow"; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            return "gen-" + (++generationCounter);
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            upsertStarted.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("SlowSink.upsert: release never came");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
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
