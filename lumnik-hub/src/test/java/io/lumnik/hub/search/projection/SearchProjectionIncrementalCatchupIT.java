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

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.replaceViewAsAnOlderHubCompiledIt;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code @QuarkusTest} proof of §5c's incremental catch-up against a genuine Postgres — spec test 12
 * (design doc, Task 13 brief). {@code SearchProjectionWorkerCatchupTest} covers the mocked-collaborator
 * batch logic (spec tests 3, 4, 6); this class needs the real view/mark table/state row and a real
 * transaction, exactly the role {@code SearchProjectionFullRebuildIT} plays for §5b.
 *
 * <p>The {@code unique}/{@code yaml}/{@code searchIndexYaml}/{@code matchEntityYaml} fixtures are
 * shared via {@link SearchProjectionItSupport}; {@code createMatchTable} stays a private copy — see
 * that class's own javadoc for why.
 */
@QuarkusTest
class SearchProjectionIncrementalCatchupIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    // -- match: mode fixtures (code/name/city) -------------------------------------------------

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

    private void deleteMatchRow(String table, long tenant, String code) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM connector." + table + " WHERE tenant_id = ? AND src_code = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, code);
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

    // -- spec test 12 -----------------------------------------------------------------------------

    /** The catch-up reader reads the same view as the rebuild: an entity applied by an older hub fails it
     *  with the gesture that fixes it, not with a SQL error (#842). */
    @Test
    void catchUpOverAViewWithoutTheKeyColumnNamesTheGesture() throws Exception {
        long tenant = 842_101L;
        String entityName = unique("Catchup842");
        String table = unique("t_catchup842_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Alice", "Lyon");
        String idxName = unique("catchup842-");
        applySearchIndex(tenant, idxName, entityName, "name");

        RecordingSink rebuildSink = new RecordingSink();
        workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idxName);
        String key = rebuildSink.upserted.get(0).id();

        replaceViewAsAnOlderHubCompiledIt(adminDs, entityName, table, null);
        markStore.markKeys(tenant, idxName, entityName, List.of(key), "test-catchup");

        workerWith(new RecordingSink(), markStore, stateStore, adminDs, readonlyDs, registry).catchUp(tenant, idxName);

        SearchProjectionState state = stateStore.find(tenant, idxName).orElseThrow();
        assertThat(state.status()).isEqualTo("degraded");
        assertThat(state.lastError())
            .contains(entityName, "_canonical_key", "apply it again", "lm entity apply")
            .doesNotContain("PSQLException", "QuarkusTransactionException");
    }

    @Test
    void keyMarkStillPresentUpsertsAndGoneRowDeletesConsumingMarksAndAdvancingPosition() throws Exception {
        long tenant = 671_801_001L;
        String entityName = unique("Catchup1");
        String table = unique("t_catchup1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Alice", "Lyon");
        insertMatchRow(table, tenant, "c2", "Bob", "Paris");

        String idxName = unique("catchup1-");
        applySearchIndex(tenant, idxName, entityName, "name", "city");

        RecordingSink rebuildSink = new RecordingSink();
        SearchProjectionWorker rebuildWorker = workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry);
        rebuildWorker.rebuild(tenant, idxName);

        assertThat(rebuildSink.published).hasSize(1);
        assertThat(rebuildSink.upserted).hasSize(2);
        assertThat(markCount(tenant, idxName))
            .as("rebuild's own cleanup drains the apply's own ALL mark").isZero();

        String keyAlive = rebuildSink.upserted.stream()
            .filter(d -> "Alice".equals(d.fields().get("name"))).findFirst().orElseThrow().id();
        String keyGone = rebuildSink.upserted.stream()
            .filter(d -> "Bob".equals(d.fields().get("name"))).findFirst().orElseThrow().id();

        long positionAfterRebuild = stateStore.find(tenant, idxName).orElseThrow().position();

        // Alice's row is mutated but stays in the view; Bob's row is physically removed from it.
        updateMatchRowCity(table, tenant, "c1", "Marseille");
        deleteMatchRow(table, tenant, "c2");
        markStore.markKeys(tenant, idxName, entityName, List.of(keyAlive, keyGone), "test-catchup");

        long aliveMarkId = markIdFor(tenant, idxName, keyAlive);
        long goneMarkId = markIdFor(tenant, idxName, keyGone);

        RecordingSink catchupSink = new RecordingSink();
        SearchProjectionWorker catchupWorker = workerWith(catchupSink, markStore, stateStore, adminDs, readonlyDs, registry);
        CatchUpResult result = catchupWorker.catchUp(tenant, idxName);

        assertThat(result).isNotEqualTo(CatchUpResult.REBUILD);
        assertThat(catchupSink.upserted)
            .as("Alice's row still matches a view row -> re-projected with its updated field")
            .hasSize(1);
        assertThat(catchupSink.upserted.get(0).id()).isEqualTo(keyAlive);
        assertThat(catchupSink.upserted.get(0).fields().get("city")).isEqualTo("Marseille");

        assertThat(catchupSink.deleted)
            .as("Bob's row is gone from the view -> deleted by its own canonical key alone, "
              + "never a reconstructed key map")
            .containsExactly(List.of(keyGone));
        assertThat(catchupSink.deleteGenerations)
            .as("the live generation the rebuild published, read back from the state row")
            .containsExactly(rebuildSink.published.get(0));

        assertThat(markCount(tenant, idxName)).as("both consumed marks are gone afterward").isZero();

        Optional<SearchProjectionState> finalState = stateStore.find(tenant, idxName);
        assertThat(finalState).isPresent();
        assertThat(finalState.get().status()).isEqualTo("ready");
        assertThat(finalState.get().position())
            .as("advanced to the highest id ACTUALLY deleted — both marks applied cleanly here")
            .isEqualTo(Math.max(aliveMarkId, goneMarkId))
            .isGreaterThan(positionAfterRebuild);
    }

    // -- I2 fix: a later successful batch clears a previously degraded status --------------------

    @Test
    void aSuccessfulCatchUpBatchClearsAPreviouslyDegradedRow() throws Exception {
        long tenant = 671_803_001L;
        String entityName = unique("Catchup3");
        String table = unique("t_catchup3_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Gus", "Metz");

        String idxName = unique("catchup3-");
        applySearchIndex(tenant, idxName, entityName, "name");

        RecordingSink rebuildSink = new RecordingSink();
        workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idxName);
        String key = rebuildSink.upserted.get(0).id();

        // Seed a degraded status from an earlier failed attempt — same shape the CHECK constraint
        // requires: (status='degraded') = (last_error IS NOT NULL).
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE core.search_projection_state SET status = 'degraded', retry_count = 3, "
               + "last_error = 'boom' WHERE tenant_id = ? AND search_index = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, idxName);
            ps.executeUpdate();
        }

        markStore.markKeys(tenant, idxName, entityName, List.of(key), "test-catchup-recovery");

        RecordingSink catchupSink = new RecordingSink();
        CatchUpResult result = workerWith(catchupSink, markStore, stateStore, adminDs, readonlyDs, registry).catchUp(tenant, idxName);

        assertThat(result).isNotEqualTo(CatchUpResult.REBUILD);
        Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
        assertThat(state).isPresent();
        assertThat(state.get().status()).as("a later successful batch clears the degraded status").isEqualTo("ready");
        assertThat(state.get().retryCount()).isZero();
        assertThat(state.get().lastError()).isNull();
    }

    // -- C2 fix: a NULL returnable column in catch-up's own batched read doesn't crash it ----------

    @Test
    void catchUpOmitsANullReturnableColumnRatherThanCrashing() throws Exception {
        long tenant = 671_802_001L;
        String entityName = unique("Catchup2");
        String table = unique("t_catchup2_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Fay", "Reims");

        String idxName = unique("catchup2-");
        applySearchIndex(tenant, idxName, entityName, "name", "city");

        RecordingSink rebuildSink = new RecordingSink();
        workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idxName);
        String key = rebuildSink.upserted.get(0).id();

        // "city" is nulled out — routine in real ERP data (e.g. an optional column no source fills).
        updateMatchRowCity(table, tenant, "c1", null);
        markStore.markKeys(tenant, idxName, entityName, List.of(key), "test-catchup-null");

        RecordingSink catchupSink = new RecordingSink();
        CatchUpResult result = workerWith(catchupSink, markStore, stateStore, adminDs, readonlyDs, registry).catchUp(tenant, idxName);

        assertThat(result).isNotEqualTo(CatchUpResult.REBUILD);
        assertThat(catchupSink.upserted).hasSize(1);
        assertThat(catchupSink.upserted.get(0).fields())
            .as("the NULL column is simply absent, never present-as-null")
            .containsEntry("name", "Fay").doesNotContainKey("city");
    }

    // -- fake sink --------------------------------------------------------------------------------

    /** Records every call a real rebuild/catch-up drives a {@link ProjectionSink} through, including
     *  {@code delete} — {@code SearchProjectionFullRebuildIT}'s own {@code RecordingSink} leaves that
     *  one a no-op since no §5b scenario needs it; catch-up's "row is gone" path does. */
    private static final class RecordingSink implements ProjectionSink {
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        final List<String> published = new CopyOnWriteArrayList<>();
        final List<String> abandoned = new CopyOnWriteArrayList<>();
        final List<List<String>> deleted = new CopyOnWriteArrayList<>();
        final List<String> deleteGenerations = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            return "gen-" + (++generationCounter);
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            upserted.addAll(documents);
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {
            deleted.add(List.copyOf(ids));
            deleteGenerations.add(generation);
        }

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            published.add(generation);
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {
            abandoned.add(generation);
        }

        @Override public void purgeIndex(long tenantId, String searchIndex) {}

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
