package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.mark.MarkRow;
import io.lumnik.hub.search.mark.MatchKeyEncoding;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * #894 against a genuine Postgres: a backlog past {@code lumnik.search.projection.rebuild-backlog-threshold}
 * goes to the full rebuild — the same path an {@code ALL} mark takes, so a mark landed after its cut-off
 * survives it — and a catch-up batch reads under the rebuild's scan budget, not the container's default
 * transaction timeout. {@code SearchProjectionWorkerTest} owns the dispatch decision itself.
 */
@QuarkusTest
class SearchProjectionCatchupBacklogIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

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

    private void execute(String sql, Object... args) throws Exception {
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private List<String> pendingKeys(long tenant, String index) {
        return markStore.fetchBatch(tenant, index, 100).stream().map(MarkRow::canonicalKey).toList();
    }

    /** A match entity over rows c1..c3 of a fresh table, one SearchIndex over it, built once. */
    private String builtIndex(long tenant, String entity, String table) throws Exception {
        createMatchTable(table);
        yaml(tenant).body(matchEntityYaml(entity, table, "city")).post("/api/entities/apply").then().statusCode(200);
        for (String code : List.of("c1", "c2", "c3")) {
            execute("INSERT INTO connector." + table + " (tenant_id, src_code, src_name, src_city) VALUES (?, ?, ?, 'Lyon')",
                tenant, code, "name-" + code);
        }
        String index = unique("backlog-");
        yaml(tenant).body(searchIndexYaml(index, entity, "name", "city")).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
        workerWith(new RecordingSink(), markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, index);
        assertThat(pendingKeys(tenant, index)).as("the first build drains the apply's own ALL mark").isEmpty();
        return index;
    }

    private static String key(String code) {
        return MatchKeyEncoding.encode(List.of(code));
    }

    @Test
    void theBacklogCountIsThePairsOwnAndStopsAtTheThreshold() {
        long tenant = 894_001L;
        String index = unique("backlog-count-");
        markStore.markKeys(tenant, index, "Widget", List.of("k1", "k2", "k3"), "test-894");
        markStore.markKeys(tenant + 1, index, "Widget", List.of("k1", "k2", "k3", "k4", "k5"), "test-894");
        markStore.markKeys(tenant, index + "-other", "Widget", List.of("k1", "k2", "k3", "k4", "k5"), "test-894");

        try {
            assertThat(markStore.pendingMarksExceed(tenant, index, 2)).isTrue();
            assertThat(markStore.pendingMarksExceed(tenant, index, 3)).isFalse();
            assertThat(markStore.pendingMarksExceed(tenant, index, 4))
                .as("another tenant's marks, and another index's, never count").isFalse();
        } finally {
            // No declaration ever drains these: left behind, they would count as lagging in every later IT.
            markStore.deleteAllFor(tenant, index);
            markStore.deleteAllFor(tenant + 1, index);
            markStore.deleteAllFor(tenant, index + "-other");
        }
    }

    @Test
    void aBacklogPastTheThresholdIsRebuiltAndAMarkLandedAfterItsCutOffSurvives() throws Exception {
        long tenant = 894_002L;
        String entity = unique("Backlog");
        String table = unique("t_backlog_").toLowerCase(Locale.ROOT);
        String index = builtIndex(tenant, entity, table);

        execute("UPDATE connector." + table + " SET src_city = 'Paris' WHERE tenant_id = ?", tenant);
        markStore.markKeys(tenant, index, entity, List.of(key("c1"), key("c2"), key("c3")), "test-894");

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuildBacklogThreshold = 2;
        // A change landing after the rebuild's watermark: its mark must outlive the rebuild's cleanup.
        worker.afterWatermarkRead = () -> {
            try {
                execute("INSERT INTO connector." + table + " (tenant_id, src_code, src_name, src_city) VALUES (?, 'c4', 'name-c4', 'Nice')",
                    tenant);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            markStore.markKeys(tenant, index, entity, List.of(key("c4")), "test-894");
        };

        CatchUpResult result = worker.dispatch(tenant, index, sink, stateStore.find(tenant, index), true);

        assertThat(result).isEqualTo(CatchUpResult.IDLE);
        assertThat(sink.published).as("a fresh generation, not a catch-up into the live one").hasSize(1);
        assertThat(sink.upserted).filteredOn(d -> List.of(key("c1"), key("c2"), key("c3")).contains(d.id()))
            .extracting(d -> d.fields().get("city")).containsExactlyInAnyOrder("Paris", "Paris", "Paris");
        assertThat(stateStore.find(tenant, index).orElseThrow().liveGeneration()).isEqualTo(sink.published.get(0));
        assertThat(pendingKeys(tenant, index))
            .as("the three marks the rebuild covered are drained; the one landed after its watermark stays")
            .containsExactly(key("c4"));
    }

    @Test
    void aCatchUpBatchReadsUnderTheRebuildScanBudget() throws Exception {
        long tenant = 894_003L;
        String entity = unique("BacklogBudget");
        String table = unique("t_backlog_budget_").toLowerCase(Locale.ROOT);
        String index = builtIndex(tenant, entity, table);
        markStore.markKeys(tenant, index, entity, List.of(key("c1")), "test-894");

        SearchProjectionWorker worker = workerWith(new RecordingSink(), markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuildScanTimeoutSeconds = 1;

        // Hold the source table for longer than that budget: the batch read waits on it.
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                c.setAutoCommit(false);
                st.execute("LOCK TABLE connector." + table + " IN ACCESS EXCLUSIVE MODE");
                locked.countDown();
                Thread.sleep(3_000);
                c.commit();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CatchUpResult result = worker.catchUp(tenant, index);
        holder.get(10, TimeUnit.SECONDS);

        assertThat(result).as("a read that outlasts the configured budget fails the batch").isEqualTo(CatchUpResult.FAILED);
        assertThat(stateStore.find(tenant, index).orElseThrow().status()).isEqualTo("degraded");
        assertThat(pendingKeys(tenant, index)).as("an unapplied mark is kept for the next tick").containsExactly(key("c1"));
    }

    /**
     * #966: PostgreSQL's plan cache turns a prepared statement generic from its 10th execution on one connection, and
     * the generic plan cannot hash the {@code = ANY($1)} array — 17x slower for a match entity. {@code
     * pg_prepared_statements.generic_plans} counts exactly those executions on this session, and the pool hands the
     * same idle connection back to a sequential caller.
     */
    @Test
    void aLongDrainOnOneConnectionNeverFallsBackToTheGenericPlan() throws Exception {
        long tenant = 966_001L;
        String entity = unique("BacklogPlan");
        String table = unique("t_backlog_plan_").toLowerCase(Locale.ROOT);
        String index = builtIndex(tenant, entity, table);
        SearchProjectionWorker worker = workerWith(new RecordingSink(), markStore, stateStore, adminDs, readonlyDs, registry);

        for (int i = 0; i < 15; i++) {
            markStore.markKeys(tenant, index, entity, List.of(key("c1")), "test-966");
            assertThat(worker.catchUp(tenant, index)).isNotEqualTo(CatchUpResult.FAILED);
        }

        try (Connection c = readonlyDs.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*), coalesce(sum(generic_plans), 0) FROM pg_prepared_statements "
                 + "WHERE statement LIKE '%_canonical_key%' AND statement LIKE '%ANY(%'")) {
            rs.next();
            // Without the fix this connection holds the named statement with 6 generic executions (15 - 9 custom).
            assertThat(rs.getLong(2)).as("generic-plan executions of the batch read").isZero();
        }
    }

    private static final class RecordingSink implements ProjectionSink {
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        final List<String> published = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            return "gen-" + (++generationCounter) + "-" + System.nanoTime();
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
