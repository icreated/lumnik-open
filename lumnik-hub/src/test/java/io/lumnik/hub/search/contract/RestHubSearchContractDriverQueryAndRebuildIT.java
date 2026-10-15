package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.testkit.search.contract.CanonicalEntityDef;
import io.lumnik.testkit.search.contract.CanonicalFieldDef;
import io.lumnik.testkit.search.contract.CanonicalRow;
import io.lumnik.testkit.search.contract.CanonicalSearchResult;
import io.lumnik.testkit.search.contract.RebuildOutcome;
import io.lumnik.testkit.search.contract.RebuildTerminalOutcome;
import io.lumnik.testkit.search.contract.TenantHandle;
import io.lumnik.testkit.search.contract.UserHandle;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(SearchBackendOidcProfile.class)
@EnabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class RestHubSearchContractDriverQueryAndRebuildIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    @Inject SearchProjectionWorker worker;

    @Test
    void queryReturnsIngestedRowsAndRebuildReachesReady() {
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        UserHandle user = driver.provisionUser(tenant, Set.of());
        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task12_probe",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.applySearchIndex(tenant, "contract-suite-task12-index", entity,
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.ingestCanonicalRows(tenant, entity, List.of(new CanonicalRow("t12-1", Map.of("probe", "findme"))));
        driver.awaitProjectionConvergence(tenant, "contract-suite-task12-index", Duration.ofSeconds(10));

        CanonicalSearchResult result = driver.query(tenant, user, "findme", Map.of());
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.degraded()).isFalse();

        RebuildOutcome triggered = driver.triggerRebuild(tenant, "contract-suite-task12-index");
        assertThat(triggered).isInstanceOf(RebuildOutcome.Started.class);

        RebuildTerminalOutcome outcome =
            driver.awaitRebuildCompletion(tenant, "contract-suite-task12-index", triggered, Duration.ofSeconds(30));
        assertThat(outcome).isEqualTo(RebuildTerminalOutcome.READY);
    }

    @Test
    void aBoundUserWithoutTheMatchingScopeGetsAnEmptyNotADegradedResult() {
        // Review Focus item 3: "no scope grant" and "provider absent" must never be conflated. A
        // provider is genuinely installed and healthy here — the only thing making the result empty is
        // the caller's own scope grants, which must produce a plain, non-degraded 200 with zero hits,
        // never the 501/503 shape SearchOutageApiContractIT (Task 15) proves separately.
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        UserHandle boundElsewhere = driver.provisionUser(tenant, Set.of("scope:some-other-entity"));
        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task12_scope",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.applySearchIndex(tenant, "contract-suite-task12-scope-index", entity, entity.columns());
        driver.ingestCanonicalRows(tenant, entity, List.of(new CanonicalRow("t12-2", Map.of("probe", "findme"))));
        driver.awaitProjectionConvergence(tenant, "contract-suite-task12-scope-index", Duration.ofSeconds(10));

        CanonicalSearchResult result = driver.query(tenant, boundElsewhere, "findme", Map.of());
        assertThat(result.hits()).isEmpty();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void awaitRebuildCompletionReportsStillRunningRatherThanReadyWhenItsOwnTimeoutElapsesFirst() {
        // Review Focus item 4: a Duration.ZERO timeout can never observe even one poll iteration
        // (the `while (System.nanoTime() < deadline)` guard fails immediately), so this deterministically
        // exercises the "timed out before observing a terminal outcome" branch without needing to race
        // a real rebuild's actual completion time.
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task12_timeout",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.applySearchIndex(tenant, "contract-suite-task12-timeout-index", entity, entity.columns());

        RebuildOutcome triggered = driver.triggerRebuild(tenant, "contract-suite-task12-timeout-index");
        RebuildTerminalOutcome outcome = driver.awaitRebuildCompletion(
            tenant, "contract-suite-task12-timeout-index", triggered, Duration.ZERO);

        assertThat(outcome).isEqualTo(RebuildTerminalOutcome.STILL_RUNNING);
    }
}
