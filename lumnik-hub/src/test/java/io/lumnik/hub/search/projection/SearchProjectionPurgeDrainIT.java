package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.insertPurgeRow;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.purgeRowCount;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc §5d, D-purge-drain: a purge row for a tenant's LAST remaining index (zero valid
 * declarations left) is still discovered and drained — purge-drain reads core.search_index_purge
 * directly, never gated behind "has a valid index". A tenant deleting two of three indices drains
 * exactly those two and leaves the third's documents/marks untouched (D-tenant-purge-async: no
 * row-count inference). TenantArchivedSearchPurgeListener itself never calls the sink.
 *
 * <p>Fixtures are seeded with direct SQL against the tables purge-drain itself reads/writes
 * ({@code core.search_index}, {@code core.search_index_purge}, {@code core.search_projection_mark},
 * {@code core.search_projection_state}) rather than through the full Entity/SearchIndex apply-then-
 * delete HTTP pipeline: purge-drain's own contract never consults {@code core.search_index} at all,
 * so what matters here is the row shapes it does touch, not how they came to exist. Worker
 * construction mirrors {@code SearchProjectionFullRebuildIT}'s own idiom: built directly with {@code
 * new} and a subclassed {@link ProjectionSinkGate} returning a fake sink, rather than a CDI
 * alternative bean.
 */
@QuarkusTest
class SearchProjectionPurgeDrainIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    private SearchProjectionWorker workerWith(ProjectionSink sink) {
        ProjectionSinkGate gate = new ProjectionSinkGate() {
            @Override public Optional<ProjectionSink> resolve() { return Optional.of(sink); }
        };
        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.adminDs = adminDs;
        return worker;
    }

    // -- fixtures ---------------------------------------------------------------------------------

    private void insertValidSearchIndex(long tenant, String name, String entity) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_index (tenant_id, name, entity, language, manifest_yaml, manifest, status) "
               + "VALUES (?, ?, ?, 'en', ?, '{}'::jsonb, 'valid')")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, entity);
            ps.setString(4, searchIndexYaml(name, entity, "name"));
            ps.executeUpdate();
        }
    }

    private void deleteSearchIndex(long tenant, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM core.search_index WHERE tenant_id = ? AND name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void insertState(long tenant, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_projection_state "
               + "(tenant_id, search_index, provider_name, live_generation, position, status) "
               + "VALUES (?, ?, 'fake', 'gen-1', 5, 'ready')")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void insertKeyMark(long tenant, String name, String entity, String canonicalKey) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_projection_mark "
               + "(tenant_id, search_index, entity, mark_kind, canonical_key, reason) "
               + "VALUES (?, ?, ?, 'KEY', ?, 'test')")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, entity);
            ps.setString(4, canonicalKey);
            ps.executeUpdate();
        }
    }

    private long markCount(long tenant, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // -- 1: last remaining index's purge row is still discovered and drained ---------------------

    @Test
    void lastRemainingIndexPurgeIsStillDrained() throws Exception {
        long tenant = 671_614_001L;
        String entity = unique("Purge1Entity");
        String idxName = unique("purge1-");

        // Zero remaining valid declarations for this tenant — the row this whole test proves is
        // still drained despite that.
        insertPurgeRow(adminDs, tenant, idxName, entity);
        insertState(tenant, idxName);
        insertKeyMark(tenant, idxName, entity, "k1");
        insertKeyMark(tenant, idxName, entity, "k2");

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink);
        worker.purgeDrain();

        // .contains, not .containsExactly: purgeDrain reads core.search_index_purge across the WHOLE
        // deployment, and other IT classes in this same @QuarkusTest run leave their own undrained
        // purge rows behind (nothing else in the codebase drains them) — this call legitimately sees
        // and drains those too. What THIS test proves is that its own (tenant, idxName) pair — with
        // zero remaining valid declarations — is among what got drained, not that it is the only one.
        assertThat(sink.purged).contains(new Purged(tenant, idxName));
        assertThat(purgeRowCount(adminDs, tenant, idxName)).as("purge row drained").isZero();
        assertThat(stateStore.find(tenant, idxName)).as("state row gone").isEmpty();
        assertThat(markCount(tenant, idxName)).as("every mark for the pair gone").isZero();
    }

    // -- 2: purging 2 of 3 indices drains only those two, the 3rd untouched ------------------------

    @Test
    void tenantDeletingTwoOfThreeIndicesDrainsOnlyThoseTwo() throws Exception {
        long tenant = 671_614_002L;
        // One SearchIndex per Entity (#832): three indices of one tenant sit over three entities.
        String entity = unique("Purge2Entity");
        String entityA = entity + "A";
        String entityB = entity + "B";
        String entityC = entity + "C";
        String idx1 = unique("purge2a-");
        String idx2 = unique("purge2b-");
        String idx3 = unique("purge2c-");

        insertValidSearchIndex(tenant, idx1, entityA);
        insertValidSearchIndex(tenant, idx2, entityB);
        insertValidSearchIndex(tenant, idx3, entityC);
        insertState(tenant, idx1);
        insertState(tenant, idx2);
        insertState(tenant, idx3);
        insertKeyMark(tenant, idx1, entityA, "k1");
        insertKeyMark(tenant, idx2, entityB, "k1");
        insertKeyMark(tenant, idx3, entityC, "k1");

        // Simulate deleting 2 of the 3 declarations: the row removed, its purge obligation written —
        // deleteWithPurge's own shape (SearchIndexStore), reproduced directly rather than through HTTP.
        deleteSearchIndex(tenant, idx1);
        insertPurgeRow(adminDs, tenant, idx1, entityA);
        deleteSearchIndex(tenant, idx2);
        insertPurgeRow(adminDs, tenant, idx2, entityB);

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink);
        worker.purgeDrain();

        // Same posture as test 1: purgeDrain sees the whole deployment's undrained rows, so this
        // asserts idx1/idx2 are AMONG the drained pairs and idx3 is NOT — not that these are the
        // only two the sink was ever called with this tick.
        assertThat(sink.purged).contains(new Purged(tenant, idx1), new Purged(tenant, idx2))
            .doesNotContain(new Purged(tenant, idx3));

        assertThat(purgeRowCount(adminDs, tenant, idx1)).isZero();
        assertThat(purgeRowCount(adminDs, tenant, idx2)).isZero();
        assertThat(stateStore.find(tenant, idx1)).isEmpty();
        assertThat(stateStore.find(tenant, idx2)).isEmpty();
        assertThat(markCount(tenant, idx1)).isZero();
        assertThat(markCount(tenant, idx2)).isZero();

        // The third index was never purged — its declaration, state and marks are untouched.
        assertThat(purgeRowCount(adminDs, tenant, idx3)).isZero();
        assertThat(stateStore.find(tenant, idx3)).as("untouched state row for the 3rd index").isPresent();
        assertThat(markCount(tenant, idx3)).as("untouched marks for the 3rd index").isEqualTo(1L);
    }

    // -- 3: a stale purge row superseded by a same-name reapply must not wipe the new declaration ---

    @Test
    void staleReapplyPurgeRowIsRetiredWithoutDestroyingTheReappliedIndex() throws Exception {
        long tenant = 671_614_003L;
        String entity = unique("Purge3Entity");
        String idxName = unique("purge3-");

        // The original declaration is deleted (queuing a purge row for it), then the SAME name is
        // reapplied and its own rebuild leaves fresh state/marks — all before purgeDrain gets to the
        // stale row (e.g. it lost a lock race on an earlier tick and was retried, per the class's own
        // "retried next tick" posture).
        insertValidSearchIndex(tenant, idxName, entity);
        deleteSearchIndex(tenant, idxName);
        insertPurgeRow(adminDs, tenant, idxName, entity);

        insertValidSearchIndex(tenant, idxName, entity); // the reapply
        insertState(tenant, idxName);
        insertKeyMark(tenant, idxName, entity, "k1");

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink);
        worker.purgeDrain();

        assertThat(sink.purged).as("reapplied index must never be purged")
            .doesNotContain(new Purged(tenant, idxName));
        assertThat(purgeRowCount(adminDs, tenant, idxName)).as("stale purge row retired").isZero();
        assertThat(stateStore.find(tenant, idxName)).as("reapplied state row untouched").isPresent();
        assertThat(markCount(tenant, idxName)).as("reapplied marks untouched").isEqualTo(1L);
    }

    // -- 4: a reapply landing DURING the destructive actions is caught and rebuilt right away ------

    @Test
    void reapplyDuringThePurgeItselfIsCaughtAndRebuiltImmediately() throws Exception {
        long tenant = 671_614_004L;
        String entity = unique("Purge4Entity");
        String idxName = unique("purge4-");

        // No live declaration at all when purgeDrain starts — this is a genuine purge, not test 3's
        // already-stale one. The reapply lands mid-flight, via the afterPurgeAction test hook, right
        // after this row's own destructive actions have already committed.
        insertPurgeRow(adminDs, tenant, idxName, entity);

        RecordingSink sink = new RecordingSink();
        RecordingRebuildWorker worker = new RecordingRebuildWorker(gateOf(sink), markStore, stateStore);
        worker.adminDs = adminDs;
        worker.afterPurgeAction = (rowTenantId, rowName) -> {
            // purgeDrain drains the WHOLE deployment's backlog (same posture as tests 1/2) — this must
            // react only to THIS test's own pair, not whichever unrelated row this tick happens to
            // reach first.
            if (rowTenantId == tenant && idxName.equals(rowName)) {
                try {
                    insertValidSearchIndex(tenant, idxName, entity);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        // A shared DevServices Postgres can carry a sizeable backlog of OTHER tests' own undrained
        // purge rows (nothing else in the codebase drains them) ahead of this one in FIFO (id) order,
        // spilling this row past a single tick's PURGE_BATCH_SIZE — bounded retry, exactly the "drains
        // over however many more ticks it takes" contract fetchUndrainedPurgeRows itself documents.
        for (int i = 0; i < 20 && purgeRowCount(adminDs, tenant, idxName) > 0; i++) {
            worker.purgeDrain();
        }

        assertThat(sink.purged).as("the genuine purge still ran").contains(new Purged(tenant, idxName));
        assertThat(purgeRowCount(adminDs, tenant, idxName)).as("purge row retired").isZero();
        assertThat(worker.rebuildCalls)
            .as("the reapply that raced in is caught and rebuilt right away, not left for the next tick")
            .containsExactly(new Dispatch(tenant, idxName));
    }

    private ProjectionSinkGate gateOf(ProjectionSink sink) {
        return new ProjectionSinkGate() {
            @Override public Optional<ProjectionSink> resolve() { return Optional.of(sink); }
        };
    }

    // -- 5: a row that keeps failing demotes itself instead of starving healthy rows behind it ------

    @Test
    void aRowThatKeepsFailingDemotesItselfBehindNotYetAttemptedRows() throws Exception {
        long tenant = 671_614_005L;
        String entity = unique("Purge5Entity");
        String failingIdx = unique("purge5-failing-");

        // Inserted, and attempted, before the not-yet-attempted row below even exists.
        insertPurgeRow(adminDs, tenant, failingIdx, entity);

        PartiallyThrowingSink sink = new PartiallyThrowingSink(tenant, failingIdx);
        SearchProjectionWorker worker = new SearchProjectionWorker(gateOf(sink), markStore, stateStore);
        worker.adminDs = adminDs;
        // Shrunk to 1 (test-only seam, never touched in production): with the real 500-row batch,
        // every row this test ever creates fits in a single fetch regardless of ordering, proving
        // nothing. At batch size 1, a plain "ORDER BY id" would fetch failingIdx (the lower id)
        // forever and never reach healthyIdx below — reproducing the overflow scenario without
        // needing hundreds of real rows.
        worker.purgeBatchSize = 1;

        // A shared DevServices Postgres can carry a sizeable backlog of OTHER tests' own undrained
        // purge rows, every one of them a LOWER id than this test's own (freshly inserted) rows —
        // at batch size 1, each drains or demotes exactly one row per call, so this must work through
        // the whole pre-existing backlog before ever reaching failingIdx itself.
        for (int i = 0; i < 1000 && lastAttemptedAt(tenant, failingIdx).isEmpty(); i++) {
            worker.purgeDrain();
        }
        assertThat(lastAttemptedAt(tenant, failingIdx)).as("failingIdx was attempted at least once").isPresent();
        assertThat(purgeRowCount(adminDs, tenant, failingIdx))
            .as("the failing row is retried, never dropped").isEqualTo(1L);

        // A never-attempted row, inserted only NOW — a higher id, so a plain "ORDER BY id" fetch
        // would never reach it while failingIdx (a lower id) keeps re-occupying the front of the
        // queue on every tick.
        String healthyIdx = unique("purge5-healthy-");
        insertPurgeRow(adminDs, tenant, healthyIdx, entity);

        for (int i = 0; i < 20 && purgeRowCount(adminDs, tenant, healthyIdx) > 0; i++) {
            worker.purgeDrain();
        }

        assertThat(sink.purged)
            .as("the never-attempted, higher-id row is reached ahead of the still-failing, lower-id one")
            .contains(new Purged(tenant, healthyIdx));
        assertThat(purgeRowCount(adminDs, tenant, healthyIdx)).as("healthy row drained").isZero();
        assertThat(purgeRowCount(adminDs, tenant, failingIdx))
            .as("the failing row is still queued, not lost").isEqualTo(1L);
    }

    private Optional<Instant> lastAttemptedAt(long tenant, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT last_attempted_at FROM core.search_index_purge WHERE tenant_id = ? AND name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Timestamp ts = rs.getTimestamp(1);
                return Optional.ofNullable(ts).map(Timestamp::toInstant);
            }
        }
    }

    /** Throws from {@code purgeIndex} for exactly one named (tenant, index) pair, standing in for a
     *  ProjectionSink outage or a poisoned entity — every other pair purges normally. */
    private static final class PartiallyThrowingSink implements ProjectionSink {
        final List<Purged> purged = new CopyOnWriteArrayList<>();
        private final long throwingTenant;
        private final String throwingIndex;

        PartiallyThrowingSink(long throwingTenant, String throwingIndex) {
            this.throwingTenant = throwingTenant;
            this.throwingIndex = throwingIndex;
        }

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            throw new UnsupportedOperationException("purge-drain never begins a generation");
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            throw new UnsupportedOperationException("purge-drain never upserts");
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {
            throw new UnsupportedOperationException("purge-drain never deletes by id");
        }

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            throw new UnsupportedOperationException("purge-drain never publishes");
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {
            throw new UnsupportedOperationException("purge-drain never abandons");
        }

        @Override public void purgeIndex(long tenantId, String searchIndex) {
            if (tenantId == throwingTenant && searchIndex.equals(throwingIndex)) {
                throw new RuntimeException("boom: simulated sink outage for this one pair");
            }
            purged.add(new Purged(tenantId, searchIndex));
        }

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }

    private record Dispatch(long tenantId, String index) {}

    /** Records {@link #rebuild} calls instead of running the real thing — proving purgeDrain's own
     *  call shape (test 4) without needing a full valid manifest/entity view for a real rebuild. */
    private static final class RecordingRebuildWorker extends SearchProjectionWorker {
        final List<Dispatch> rebuildCalls = new CopyOnWriteArrayList<>();

        RecordingRebuildWorker(ProjectionSinkGate gate, SearchProjectionMarkStore markStore,
                               SearchProjectionStateStore stateStore) {
            super(gate, markStore, stateStore);
        }

        @Override void rebuild(long tenantId, String index) {
            rebuildCalls.add(new Dispatch(tenantId, index));
        }
    }

    // -- fake sink ----------------------------------------------------------------------------------

    private record Purged(long tenantId, String searchIndex) {}

    /** Records every {@code purgeIndex} call a real purge-drain drives — no real search backend
     *  needed, this is about proving the worker's own call shape (Task 14 brief). */
    private static final class RecordingSink implements ProjectionSink {
        final List<Purged> purged = new CopyOnWriteArrayList<>();

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            throw new UnsupportedOperationException("purge-drain never begins a generation");
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            throw new UnsupportedOperationException("purge-drain never upserts");
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {
            throw new UnsupportedOperationException("purge-drain never deletes by id");
        }

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            throw new UnsupportedOperationException("purge-drain never publishes");
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {
            throw new UnsupportedOperationException("purge-drain never abandons");
        }

        @Override public void purgeIndex(long tenantId, String searchIndex) {
            purged.add(new Purged(tenantId, searchIndex));
        }

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
