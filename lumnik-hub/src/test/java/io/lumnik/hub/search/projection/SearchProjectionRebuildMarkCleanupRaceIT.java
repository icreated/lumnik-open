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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code @QuarkusTest} proof of #969 — #712's commit-order race on the REBUILD's mark cleanup. A mark
 * whose {@code id} sits below the rebuild's watermark but whose transaction commits only AFTER the
 * scan opened its snapshot is not reflected in the rebuilt generation; the cleanup must leave it for
 * the next catch-up instead of deleting it with {@code id <= watermark}.
 */
@QuarkusTest
class SearchProjectionRebuildMarkCleanupRaceIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

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

    private long markIdFor(long tenant, String indexName, String canonicalKey) throws Exception {
        try (Connection c = adminDs.getConnection()) {
            return markIdFor(c, tenant, indexName, canonicalKey);
        }
    }

    /** Same query, on the CALLER's own open connection — needed to read session A's own uncommitted
     *  insert back, which no other connection can see yet. */
    private long markIdFor(Connection c, long tenant, String indexName, String canonicalKey) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
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

    private List<Long> markIdsFor(long tenant, String indexName, String canonicalKey) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ? "
               + "AND canonical_key = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            ps.setString(3, canonicalKey);
            List<Long> ids = new ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
            return ids;
        }
    }

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

    @Test
    void aLowerIdMarkCommittedDuringTheScanSurvivesTheRebuildsCleanup() throws Exception {
        long tenant = 969_001_001L;
        String entityName = unique("Race969");
        String table = unique("t_race969_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "k1", "RowA");
        insertMatchRow(table, tenant, "k2", "RowB");

        String idxName = unique("race969-");
        applySearchIndex(tenant, idxName, entityName, "name");

        // The REAL canonical keys (MatchKeyEncoding is one-way): learn them from a first rebuild.
        RecordingSink firstSink = new RecordingSink();
        workerWith(firstSink, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idxName);
        String canonicalKey1 = firstSink.upserted.stream()
            .filter(d -> "k1".equals(d.key().get("code"))).findFirst().orElseThrow().id();
        String canonicalKey2 = firstSink.upserted.stream()
            .filter(d -> "k2".equals(d.key().get("code"))).findFirst().orElseThrow().id();
        assertThat(markCount(tenant, idxName)).as("the first rebuild drained the index-apply mark").isZero();

        // Session A: marks k1 (LOWER id) and stays open. Session B: marks k2 (HIGHER id), commits.
        Connection sessionA = adminDs.getConnection();
        sessionA.setAutoCommit(false);
        try {
            markStore.markKeys(sessionA, tenant, idxName, entityName, List.of(canonicalKey1), "sessionA");
            long k1MarkId = markIdFor(sessionA, tenant, idxName, canonicalKey1);
            markStore.markKeys(tenant, idxName, entityName, List.of(canonicalKey2), "sessionB");
            assertThat(markIdFor(tenant, idxName, canonicalKey2)).isGreaterThan(k1MarkId);

            // The rebuild reads its watermark (= k2's id, k1's mark invisible), scans, and only THEN
            // does session A commit — after the scan's snapshot, before the cleanup.
            RecordingSink sink = new RecordingSink();
            SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
            SearchProjectionWorker.RowScanner realScan = worker.rowScanner;
            worker.rowScanner = (t, i, e, m, s, g, r) -> {
                realScan.scan(t, i, e, m, s, g, r);
                sessionA.commit();
            };
            worker.rebuild(tenant, idxName);

            assertThat(markIdsFor(tenant, idxName, canonicalKey1))
                .as("k1's mark committed after the scan opened: the rebuilt generation does not reflect "
                  + "it, so the cleanup must not delete it (#969)")
                .containsExactly(k1MarkId);
            assertThat(markCount(tenant, idxName)).as("k2's mark, visible at the watermark, is drained")
                .isEqualTo(1L);
        } finally {
            sessionA.close();
        }
    }

    private static final class RecordingSink implements ProjectionSink {
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
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

        @Override public void publish(long tenantId, String searchIndex, String generation) {}

        @Override public void abandon(long tenantId, String searchIndex, String generation) {}

        @Override public void purgeIndex(long tenantId, String searchIndex) {}

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
