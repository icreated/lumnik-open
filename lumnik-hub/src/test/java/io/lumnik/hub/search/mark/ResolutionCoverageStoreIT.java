package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore.UncoveredBound;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRun;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markRunExempt;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.runFence;
import static org.assertj.core.api.Assertions.assertThat;

/** #748: the coverage ledger against a real Postgres. Each test owns its tenant. */
@QuarkusTest
class ResolutionCoverageStoreIT {

    @Inject ResolutionCoverageStore store;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void noRunsMeansNone() {
        assertThat(store.uncoveredBound(748_101, "e")).isEqualTo(UncoveredBound.NONE);
    }

    @Test
    void aRunningRunBlocksAtItsFence() throws Exception {
        UUID run = insertRun(adminDs, 748_102, "Running", false);
        UncoveredBound bound = store.uncoveredBound(748_102, "e");
        assertThat(bound.unknown()).isFalse();
        assertThat(bound.earliestFence()).isEqualTo(runFence(adminDs, run));
    }

    @Test
    void theEarliestUncoveredFenceWins() throws Exception {
        UUID older = insertRun(adminDs, 748_103, "Completed", false);
        Thread.sleep(5);
        insertRun(adminDs, 748_103, "Completed", false);
        assertThat(store.uncoveredBound(748_103, "e").earliestFence()).isEqualTo(runFence(adminDs, older));
    }

    @Test
    void aNullFenceUncoveredRunIsUnknown() throws Exception {
        insertRun(adminDs, 748_104, "Completed", true);
        assertThat(store.uncoveredBound(748_104, "e").unknown()).isTrue();
    }

    @Test
    void coverageIsPerEntity() throws Exception {
        UUID run = insertRun(adminDs, 748_105, "Completed", false);
        store.cover(748_105, "a", List.of(run));
        assertThat(store.uncoveredBound(748_105, "a")).isEqualTo(UncoveredBound.NONE);
        assertThat(store.uncoveredBound(748_105, "b").earliestFence()).isEqualTo(runFence(adminDs, run));
    }

    @Test
    void coveringReleasesAndIsIdempotent() throws Exception {
        UUID run = insertRun(adminDs, 748_106, "Completed", false);
        store.cover(748_106, "e", List.of(run));
        store.cover(748_106, "e", List.of(run));
        assertThat(store.uncoveredBound(748_106, "e")).isEqualTo(UncoveredBound.NONE);
    }

    @Test
    void finishedUncoveredRunsExcludesRunningAndCoveredButKeepsNullFence() throws Exception {
        long tenant = 748_107;
        insertRun(adminDs, tenant, "Running", false);
        UUID completed = insertRun(adminDs, tenant, "Completed", false);
        UUID failed = insertRun(adminDs, tenant, "Failed", false);
        UUID historical = insertRun(adminDs, tenant, "Completed", true);
        UUID covered = insertRun(adminDs, tenant, "Completed", false);
        store.cover(tenant, "e", List.of(covered));

        assertThat(store.finishedUncoveredRuns(tenant, "e")).containsExactlyInAnyOrder(completed, failed, historical);
    }

    @Test
    void otherTenantsRunsAreInvisible() throws Exception {
        insertRun(adminDs, 748_108, "Running", false);
        insertRun(adminDs, 748_108, "Completed", true);
        assertThat(store.uncoveredBound(748_109, "e")).isEqualTo(UncoveredBound.NONE);
        assertThat(store.finishedUncoveredRuns(748_109, "e")).isEmpty();
    }

    @Test
    void aRunThatNeverExecutedNeitherBlocksNorEntersTheSnapshot() throws Exception {
        long tenant = 748_110;
        UUID skipped = insertRun(adminDs, tenant, "Cancelled", false);   // e.g. skipped: another run is already active
        markRunExempt(adminDs, skipped);
        UUID skippedNullFence = insertRun(adminDs, tenant, "Cancelled", true);
        markRunExempt(adminDs, skippedNullFence);

        assertThat(store.uncoveredBound(tenant, "e")).isEqualTo(UncoveredBound.NONE);
        assertThat(store.finishedUncoveredRuns(tenant, "e")).isEmpty();
    }

    @Test
    void coverIgnoresRunsThatNoLongerExistAndStillCoversTheRest() throws Exception {
        long tenant = 748_111;
        UUID run = insertRun(adminDs, tenant, "Completed", false);

        store.cover(tenant, "e", List.of(UUID.randomUUID(), run));   // one id was cascade-deleted meanwhile

        assertThat(store.uncoveredBound(tenant, "e")).isEqualTo(UncoveredBound.NONE);
    }

    @Test
    void coverNeverCoversAnotherTenantsRun() throws Exception {
        UUID run = insertRun(adminDs, 748_112, "Completed", false);

        store.cover(748_113, "e", List.of(run));

        assertThat(store.uncoveredBound(748_112, "e").earliestFence()).isNotNull();
    }
}
