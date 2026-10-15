package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec test 14 (gauge half): the two lag gauges are genuine deployment-wide aggregates, not
 * accidentally scoped to one tenant — one tenant's own bigger backlog and another tenant's smaller
 * one both count, and the reported max is the LARGER of the two.
 *
 * <p>Marks are inserted by raw SQL with an explicit {@code marked_at} — {@code markKeys}' own {@code
 * now()} default cannot be dialed back, and #703's own computation ({@link
 * SearchProjectionMarkStore#oldestPendingMarkedAtAdmin()}) reads {@code core.search_projection_mark}
 * directly, independent of whether a valid SearchIndex/Entity declaration exists for the pair at
 * all — so this test never applies either.
 *
 * <p><b>Why two ticks, not an exact count after one.</b> {@code discoveryTick()} is deliberately
 * cross-tenant (same posture {@code SearchProjectionMarkIdOrderRaceIT}/{@code
 * SearchProjectionRunReaperIT} already document): the shared DevServices Postgres this suite runs
 * against may carry OTHER tests' own leftover valid SearchIndex rows and pending marks, and this
 * worker's own real {@code rebuild}/{@code catchUp} dispatch could legitimately drain some of them
 * mid-tick. An exact-count assertion right after inserting two marks would be exactly the "bare
 * count of everything the call happened to dispatch" those siblings' own javadoc warns against. This
 * test instead proves the aggregate genuinely reflects BOTH tenants without depending on the shared
 * table's exact size: tick 1 shows the max lag dominated by tenant A's much older mark, tick 2 (after
 * removing ONLY tenant A's mark) shows the max lag fall below tenant A's own magnitude while still
 * reflecting tenant B's — a bug that silently scoped the aggregate to whichever tenant happens to be
 * seen first would fail exactly this second assertion.
 */
@QuarkusTest
class SearchProjectionLagMetricsIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry entityRegistry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;
    @Inject SearchProjectionMetrics metrics;
    @Inject MeterRegistry registry;

    private void insertMarkWithMarkedAt(long tenant, String index, Instant markedAt) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_projection_mark "
               + "(tenant_id, search_index, entity, mark_kind, canonical_key, reason, marked_at) "
               + "VALUES (?, ?, 'widget', 'KEY', 'k1', 'lag-metrics-it', ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, index);
            ps.setTimestamp(3, Timestamp.from(markedAt));
            ps.executeUpdate();
        }
    }

    private void deleteMark(long tenant, String index) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, index);
            ps.executeUpdate();
        }
    }

    @Test
    void discoveryTickRecordsTheMaxLagAcrossTwoTenantsLaggingDifferently() throws Exception {
        long tenantA = unique("703014").hashCode() & 0x7fffffffL; // any distinct, never-declared tenant id
        long tenantB = tenantA + 1;
        String indexA = unique("lag-a-");
        String indexB = unique("lag-b-");

        Instant now = Instant.now();
        insertMarkWithMarkedAt(tenantA, indexA, now.minusSeconds(10_000)); // much larger lag
        insertMarkWithMarkedAt(tenantB, indexB, now.minusSeconds(60));     // smaller lag, still > 0

        try {
            // Real, CDI-managed SearchProjectionWorker built the same way this package's other
            // discoveryTick-level ITs are (workerWith), but with its metrics field swapped for the
            // REAL, CDI-managed SearchProjectionMetrics bean — workerWith's own default wires a fresh
            // instance backed by a throwaway SimpleMeterRegistry that registerGauges() never touched
            // (no CDI @PostConstruct outside the container), so reading a gauge back from IT would
            // otherwise hit MeterNotFoundException. The gauge itself is read back through the
            // separately-injected, real MeterRegistry bean — NOT through metrics.registry: metrics is
            // itself a CDI client proxy, and a direct field read on a proxy returns the proxy's own
            // (never-injected) field, not the real singleton's; only method calls delegate correctly.
            SearchProjectionWorker worker =
                workerWith(new RecordingSink(), markStore, stateStore, adminDs, readonlyDs, entityRegistry);
            worker.metrics = metrics;

            worker.discoveryTick();

            double maxLagSeconds = registry.get("lumnik_search_projection_lag_seconds").gauge().value();
            double laggingIndexes = registry.get("lumnik_search_projection_lagging_indexes").gauge().value();

            assertThat(maxLagSeconds)
                .as("dominated by tenant A's much older mark")
                .isGreaterThanOrEqualTo(10_000);
            assertThat(laggingIndexes)
                .as("both tenants' pairs counted, whatever else the shared DB already carried")
                .isGreaterThanOrEqualTo(2);

            // Remove ONLY tenant A's mark: if the aggregate were secretly scoped to one tenant (or
            // just to whichever pair sorts first), the gauge would either stay pinned near 10_000 or
            // drop to zero. The correct, genuinely deployment-wide aggregate falls to reflect tenant
            // B's own, much smaller lag instead — proving B's contribution was real, not incidental.
            deleteMark(tenantA, indexA);
            worker.discoveryTick();

            double maxLagSecondsAfter = registry.get("lumnik_search_projection_lag_seconds").gauge().value();
            assertThat(maxLagSecondsAfter)
                .as("tenant A's own mark is gone; the gauge must fall to reflect tenant B's, not stay pinned")
                .isLessThan(10_000)
                .isGreaterThanOrEqualTo(30);
        } finally {
            deleteMark(tenantA, indexA);
            deleteMark(tenantB, indexB);
        }
    }

    /**
     * Copilot finding: {@code recordLagGauges} used to gate the {@code lagging} COUNT on {@code
     * lagSeconds > 0}, so a mark landed within the same second as {@code now()} reported
     * laggingIndexes=0 for its own pair even though it is genuinely pending — {@code
     * oldestPendingMarkedAtAdmin}'s own {@code GROUP BY} only ever returns a pair that HAS pending
     * work, so presence alone must count, not a lag threshold.
     *
     * <p>Same delta technique as the test above, for the same reason (shared DB, other tests' own
     * leftover marks): removing exactly THIS pair's own mark is what proves it was the one counted,
     * not incidental noise.
     */
    @Test
    void recordLagGaugesCountsAPendingMarkFromTheSameSecond() throws Exception {
        long tenant = unique("703015").hashCode() & 0x7fffffffL;
        String index = unique("lag-same-second-");

        insertMarkWithMarkedAt(tenant, index, Instant.now()); // lagSeconds == 0 for this pair

        try {
            SearchProjectionWorker worker =
                workerWith(new RecordingSink(), markStore, stateStore, adminDs, readonlyDs, entityRegistry);
            worker.metrics = metrics;

            worker.discoveryTick();
            double laggingWithMark = registry.get("lumnik_search_projection_lagging_indexes").gauge().value();

            deleteMark(tenant, index);
            worker.discoveryTick();
            double laggingWithoutMark = registry.get("lumnik_search_projection_lagging_indexes").gauge().value();

            assertThat(laggingWithMark - laggingWithoutMark)
                .as("this pair's own same-second mark must count while present, and no longer once removed")
                .isEqualTo(1);
        } finally {
            deleteMark(tenant, index);
        }
    }

    private static final class RecordingSink implements ProjectionSink {
        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }
        @Override public String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) { return "gen"; }
        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {}
        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}
        @Override public void publish(long tenantId, String searchIndex, String generation) {}
        @Override public void abandon(long tenantId, String searchIndex, String generation) {}
        @Override public void purgeIndex(long tenantId, String searchIndex) {}
        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
