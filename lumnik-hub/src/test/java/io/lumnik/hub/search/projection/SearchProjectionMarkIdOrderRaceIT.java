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
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code @QuarkusTest} proof of the #712 commit-order race: {@code core.search_projection_mark.id}
 * order is NOT commit order, so neither the dispatch decision nor the batch fetch may ever compare
 * against it.
 *
 * <p>Real two-session reproduction, unlike {@code SearchProjectionConcurrentMarkIT}'s same-thread
 * hook idiom — that test pins a re-mark landing at one precise instant inside a single call; this one
 * pins a genuinely lower {@code id} becoming visible only AFTER a higher one has already been drained,
 * which needs two actual Postgres sessions (an uncommitted transaction on one, a committed insert on
 * the other), not just program order on one thread:
 *
 * <ol>
 *   <li>Session A opens a transaction, marks key {@code k1} (gets the LOWER id), stays open —
 *       invisible to every other session.</li>
 *   <li>Session B (a separate, auto-committing connection) marks key {@code k2} (gets the HIGHER id)
 *       and commits immediately — visible right away.</li>
 *   <li>Tick 1 runs while A is still open: only {@code k2}'s mark exists as far as any query can see,
 *       so only {@code k2} is projected, and {@code position} advances to {@code k2}'s id.</li>
 *   <li>Session A commits. {@code k1}'s mark — a LOWER id than the {@code position} tick 1 already
 *       advanced to — is now visible for the first time.</li>
 *   <li>Tick 2 (the real {@link SearchProjectionWorker#discoveryTick()} pipeline, not a direct {@code
 *       catchUp} call) must still find and drain it: {@code k1} gets projected, nothing is silently
 *       skipped forever.</li>
 * </ol>
 */
@QuarkusTest
class SearchProjectionMarkIdOrderRaceIT {

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

    @Test
    void aLowerIdMarkThatCommitsAfterAHigherOneWasAlreadyDrainedIsStillAppliedNextTick() throws Exception {
        long tenant = 712_001_001L;
        String entityName = unique("Race712");
        String table = unique("t_race712_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "k1", "RowA");
        insertMatchRow(table, tenant, "k2", "RowB");

        String idxName = unique("race712-");
        applySearchIndex(tenant, idxName, entityName, "name");

        // Establishes liveGeneration/provider_name so the next tick's dispatch chooses catch-up, not
        // a full rebuild — both rows already exist, so the rebuild itself already projects both; the
        // race below is only about the marks each row's SEPARATE catch-up gets, from here on. Also
        // gives us the REAL, MatchKeyEncoding-encoded canonical keys — a mark's own canonical_key is
        // never the raw match-column value (design doc §1: MatchKeyEncoding is one-way), so tick 1/2
        // below re-mark by these opaque strings, exactly like SearchProjectionConcurrentMarkIT does.
        RecordingSink rebuildSink = new RecordingSink();
        SearchProjectionWorker rebuildWorker = workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry);
        rebuildWorker.rebuild(tenant, idxName);
        assertThat(rebuildSink.upserted).hasSize(2);
        String canonicalKey1 = rebuildSink.upserted.stream()
            .filter(d -> "k1".equals(d.key().get("code"))).findFirst().orElseThrow().id();
        String canonicalKey2 = rebuildSink.upserted.stream()
            .filter(d -> "k2".equals(d.key().get("code"))).findFirst().orElseThrow().id();

        // Session A: marks k1, gets the LOWER id, stays open — invisible to every other session.
        Connection sessionA = adminDs.getConnection();
        sessionA.setAutoCommit(false);
        markStore.markKeys(sessionA, tenant, idxName, entityName, List.of(canonicalKey1), "sessionA");
        long k1MarkId = markIdFor(sessionA, tenant, idxName, canonicalKey1);

        // Session B: a separate, auto-committing connection — marks k2, gets the HIGHER id (A's
        // nextval() already fired first), commits immediately.
        markStore.markKeys(tenant, idxName, entityName, List.of(canonicalKey2), "sessionB");
        long k2MarkId = markIdFor(tenant, idxName, canonicalKey2);
        assertThat(k2MarkId).as("B's insert happened after A's, so its id is higher, even though A "
            + "commits later").isGreaterThan(k1MarkId);

        try {
            // Tick 1, while session A is STILL OPEN: k1's mark is invisible to any other session, so
            // only k2 can possibly be fetched and applied.
            RecordingSink tick1Sink = new RecordingSink();
            SearchProjectionWorker tick1Worker = workerWith(tick1Sink, markStore, stateStore, adminDs, readonlyDs, registry);
            CatchUpResult result1 = tick1Worker.catchUp(tenant, idxName);

            assertThat(result1).isNotEqualTo(CatchUpResult.REBUILD);
            assertThat(tick1Sink.upserted).hasSize(1);
            assertThat(tick1Sink.upserted.get(0).id()).isEqualTo(canonicalKey2);
            assertThat(stateStore.find(tenant, idxName).orElseThrow().position()).isEqualTo(k2MarkId);
        } finally {
            // Session A commits now: k1's mark — a LOWER id than the position tick 1 already advanced
            // to — becomes visible for the first time.
            sessionA.commit();
            sessionA.close();
        }

        // Tick 2: the REAL discoveryTick() pipeline, not a direct catchUp() call — this is what an
        // old `position < head` dispatch gate would have skipped forever, since the mark table's own
        // head (k1MarkId, the only row left) now sits BELOW the state row's own position (k2MarkId).
        // discoveryTick() is deliberately cross-tenant (SearchProjectionRunReaperIT's own precedent):
        // a leftover valid SearchIndex from an unrelated test sharing the same DevServices Postgres
        // can add its own, unrelated docs to this shared sink, so the assertion below is scoped to
        // THIS pair's own key, never a bare count of everything the call happened to dispatch.
        RecordingSink tick2Sink = new RecordingSink();
        SearchProjectionWorker tick2Worker = workerWith(tick2Sink, markStore, stateStore, adminDs, readonlyDs, registry);
        tick2Worker.discoveryTick();

        assertThat(tick2Sink.upserted)
            .as("k1's mark is never silently skipped just because its id is lower than an "
              + "already-advanced position (#712)")
            .anyMatch(doc -> canonicalKey1.equals(doc.id()));

        // position is a monotone diagnostic only (#712): applying the lower id AFTER the higher one
        // must never regress it back down to k1MarkId.
        assertThat(stateStore.find(tenant, idxName).orElseThrow().position())
            .as("position stays at the highest id ever applied, never regresses")
            .isEqualTo(k2MarkId);
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
