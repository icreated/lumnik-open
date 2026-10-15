package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.mockito.Mockito;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.MatchFixture;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyMatchEntityAndIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markedKeys;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermark;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #896: without a Search provider — the normal Open/Pro state, and this shared test profile's unless {@code -Psearch}
 * puts one on the classpath — nothing ever drains a mark, so neither trigger of the mark producer works: a
 * run-completed event and a reconciler tick take no {@code max(_ingested_at)} over the indexed tables, write no mark
 * and leave the table without a watermark. With a provider, the same two triggers mark as before.
 *
 * <p>Off under {@code -Psearch} (SearchIndexApplyResourceIT's note): it asserts the no-provider posture, and its
 * provider half counts marks the live worker would then be draining.
 */
@QuarkusTest
@DisabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class SearchProjectionNoProviderIT {

    private static final String REASON = "ingestion-run";
    private static final ProjectionSink SINK = Mockito.mock(ProjectionSink.class);
    /** A gate with a provider resolved, for the hook and reconciler built outside CDI. */
    private static final ProjectionSinkGate INSTALLED = new ProjectionSinkGate() {
        @Override public Optional<ProjectionSink> resolve() { return Optional.of(SINK); }
    };

    @Inject SearchProjectionRunHook hook;
    @Inject SearchProjectionReconciler reconciler;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionWatermarkStore watermarkStore;

    @Test
    void withoutAProviderNeitherARunNorATickScansOrMarks() throws Exception {
        long tenant = 896_001;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "NoProvider");
        insertRow(adminDs, f.table(), tenant, "C1", "Alpha");
        insertRow(adminDs, f.table(), tenant, "C2", "Beta");

        hook.onRunCompleted(runCompleted(tenant));
        reconciler.scheduledTick();

        assertThat(markCount(adminDs, tenant, f.index(), REASON)).as("no mark nobody would drain").isZero();
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + f.table()))
            .as("no scan committed: the watermark a scan leaves behind does not exist").isZero();
    }

    @Test
    void withAProviderARunAndATickMarkAsBefore() throws Exception {
        long byRun = 896_002;
        MatchFixture run = applyMatchEntityAndIndex(adminDs, byRun, "ProviderRun");
        insertRow(adminDs, run.table(), byRun, "C1", "Alpha");
        insertRow(adminDs, run.table(), byRun, "C2", "Beta");
        hookWithProvider().onRunCompleted(runCompleted(byRun));
        assertThat(markCount(adminDs, byRun, run.index(), REASON)).isEqualTo(2L);
        assertThat(watermarkRowCount(adminDs, byRun, "connector." + run.table())).isEqualTo(1L);

        long byTick = 896_003;
        MatchFixture tick = applyMatchEntityAndIndex(adminDs, byTick, "ProviderTick");
        insertRow(adminDs, tick.table(), byTick, "C1", "Alpha");
        reconcilerWithProvider(byTick).scheduledTick();
        assertThat(markCount(adminDs, byTick, tick.index(), REASON)).isEqualTo(1L);
        assertThat(watermarkRowCount(adminDs, byTick, "connector." + tick.table())).isEqualTo(1L);
    }

    /** What makes skipping lossless: the watermark only moves when a table is scanned, so a provider that comes back
     *  finds it where it stopped and marks every row ingested while it was away. */
    @Test
    void aProviderBackAfterAGapMarksEveryRowIngestedDuringIt() throws Exception {
        long tenant = 896_004;
        MatchFixture f = applyMatchEntityAndIndex(adminDs, tenant, "Gap");
        insertRow(adminDs, f.table(), tenant, "C1", "Alpha");
        hookWithProvider().onRunCompleted(runCompleted(tenant));
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = " + tenant);   // drained
        Instant beforeGap = watermark(adminDs, tenant, "connector." + f.table());

        insertRow(adminDs, f.table(), tenant, "C2", "Beta");                                     // the provider is away
        hook.onRunCompleted(runCompleted(tenant));
        reconciler.scheduledTick();
        assertThat(markCount(adminDs, tenant, f.index(), REASON)).isZero();
        assertThat(watermark(adminDs, tenant, "connector." + f.table())).isEqualTo(beforeGap);

        hookWithProvider().onRunCompleted(runCompleted(tenant));                                  // and back
        assertThat(markedKeys(adminDs, tenant, f.index(), REASON)).contains(MatchKeyEncoding.encode(List.of("C2")));
    }

    private static ConnectorRunCompleted runCompleted(long tenant) {
        return new ConnectorRunCompleted(UUID.randomUUID(), tenant, UUID.randomUUID(), Instant.now());
    }

    /** Built outside CDI with a provider resolved, so the live worker (sharing the CDI gate) stays inert. */
    private SearchProjectionRunHook hookWithProvider() {
        SearchProjectionRunHook h = new SearchProjectionRunHook();
        h.ds = adminDs;
        h.registry = registry;
        h.markStore = markStore;
        h.watermarkStore = watermarkStore;
        h.sinkGate = INSTALLED;
        return h;
    }

    /** Sweeps {@code tenant} alone: the cross-tenant discovery is SearchProjectionReconcilerIT's, and a sweep of the
     *  whole shared database would mark other tests' tenants. */
    private SearchProjectionReconciler reconcilerWithProvider(long tenant) {
        SearchProjectionReconciler r = new SearchProjectionReconciler();
        r.ds = adminDs;
        r.markStore = new SearchProjectionMarkStore() {
            @Override public List<Long> tenantsWithValidIndex(Connection c) { return List.of(tenant); }
        };
        r.runHook = hook;
        r.sinkGate = INSTALLED;
        return r;
    }
}
