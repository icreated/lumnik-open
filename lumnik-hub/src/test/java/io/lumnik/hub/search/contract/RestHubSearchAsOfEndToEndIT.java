package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.search.mark.SearchProjectionReconciler;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.testkit.search.contract.CanonicalEntityDef;
import io.lumnik.testkit.search.contract.CanonicalFieldDef;
import io.lumnik.testkit.search.contract.CanonicalRow;
import io.lumnik.testkit.search.contract.TenantHandle;
import io.lumnik.testkit.search.contract.UserHandle;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** #730 end to end: the hub, the projection worker and the real PostgreSQL provider together — what a
 *  client of {@code /api/search} actually sees in {@code asOf}. */
@QuarkusTest
@TestProfile(SearchBackendOidcProfile.class)
@EnabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class RestHubSearchAsOfEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    @Inject SearchProjectionWorker worker;
    @Inject SearchProjectionReconciler reconciler;

    private Instant dbClock() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT clock_timestamp()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    private record Fixture(RestHubSearchContractDriver driver, TenantHandle tenant, UserHandle user,
                           CanonicalEntityDef entity, String index) {}

    private Fixture fixture(String label) {
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        CanonicalEntityDef entity = new CanonicalEntityDef("asof_e2e_" + label,
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        UserHandle user = driver.provisionUser(tenant, Set.of("scope:" + entity.name()));
        String index = "asof-e2e-" + label;
        driver.applySearchIndex(tenant, index, entity, entity.columns());
        return new Fixture(driver, tenant, user, entity, index);
    }

    @Test
    void aFreshRebuildReportsARealInstantNotTheCutover() throws Exception {
        Fixture f = fixture("rebuild");
        f.driver().ingestCanonicalRows(f.tenant(), f.entity(), List.of(new CanonicalRow("r1", Map.of("probe", "findme"))));
        f.driver().awaitProjectionConvergence(f.tenant(), f.index(), WAIT);
        Instant after = dbClock();

        Instant asOf = f.driver().queryAsOf(f.user(), "findme");

        assertThat(asOf).as("the pg provider now reports freshness").isNotNull();
        assertThat(asOf).isBeforeOrEqualTo(after);
    }

    @Test
    void aQuietIndexFollowsTheWatermarksInsteadOfFreezingAtItsRebuildBase() throws Exception {
        Fixture f = fixture("quiet");
        f.driver().ingestCanonicalRows(f.tenant(), f.entity(), List.of(new CanonicalRow("r1", Map.of("probe", "steady"))));
        f.driver().awaitProjectionConvergence(f.tenant(), f.index(), WAIT);   // ~10 s of ticks after the rebuild
        Instant beforeSweep = dbClock();

        reconciler.sweep();          // the 1 s margin held the watermark just behind the row: this pass re-scans it
        worker.discoveryTick();      // ... the tick drains that re-mark
        reconciler.sweep();          // now nothing is new: the idle rule moves the watermark up to the safe bound
        worker.discoveryTick();      // the tick then lifts the live generation's asOf to it

        assertThat(f.driver().queryAsOf(f.user(), "steady"))
            .as("nothing changed, yet asOf is close to now — not stuck at the rebuild base ~10 s earlier")
            .isAfter(beforeSweep.minusSeconds(3));
    }

    @Test
    void aLaterIngestedChangeIsCoveredOnceItHasDrained() throws Exception {
        Fixture f = fixture("catchup");
        f.driver().ingestCanonicalRows(f.tenant(), f.entity(), List.of(new CanonicalRow("r1", Map.of("probe", "first"))));
        f.driver().awaitProjectionConvergence(f.tenant(), f.index(), WAIT);
        Instant before = dbClock().minusSeconds(5);

        f.driver().ingestCanonicalRows(f.tenant(), f.entity(), List.of(new CanonicalRow("r2", Map.of("probe", "second"))));
        f.driver().awaitProjectionConvergence(f.tenant(), f.index(), WAIT);

        assertThat(f.driver().query(f.tenant(), f.user(), "second", Map.of()).hits()).hasSize(1);
        assertThat(f.driver().queryAsOf(f.user(), "second"))
            .as("asOf has caught up past the moment just before the second ingestion").isAfter(before);
    }

    @Test
    void anOutOfBandHardDeleteIsNotClaimedAndOnlyARebuildRemovesIt() throws Exception {
        Fixture f = fixture("harddelete");
        f.driver().ingestCanonicalRows(f.tenant(), f.entity(), List.of(new CanonicalRow("r1", Map.of("probe", "gone"))));
        f.driver().awaitProjectionConvergence(f.tenant(), f.index(), WAIT);
        Instant asOfBefore = f.driver().queryAsOf(f.user(), "gone");
        assertThat(asOfBefore).isNotNull();

        f.driver().deleteCanonicalRow(f.tenant(), f.entity(), "r1");
        for (int i = 0; i < 3; i++) worker.discoveryTick();   // incremental drain only: nothing to observe (#732)

        assertThat(f.driver().query(f.tenant(), f.user(), "gone", Map.of()).hits())
            .as("#732: the deleted row is still returned — the contract does not cover hard deletes").hasSize(1);
        assertThat(f.driver().queryAsOf(f.user(), "gone")).isAfterOrEqualTo(asOfBefore);

        f.driver().awaitProjectionConvergence(f.tenant(), f.index(), WAIT);   // the documented fallback: a rebuild
        assertThat(f.driver().query(f.tenant(), f.user(), "gone", Map.of()).hits()).isEmpty();
    }
}
