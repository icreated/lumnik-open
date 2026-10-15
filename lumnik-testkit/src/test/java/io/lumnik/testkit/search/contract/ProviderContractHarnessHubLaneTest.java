package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.containerNamed;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.fixture;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.runEvery;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderContractHarnessHubLaneTest {

    @Test
    void isolationEndToEndPassesAgainstADriverThatActuallyScopesByTenantAndScope() throws Throwable {
        HubSearchContractFixture fixture = fixture(new FakeHubSearchContractDriver());
        runEvery(containerNamed(ProviderContractHarness.hubLane(fixture), "ISOLATION_END_TO_END"));
    }

    @Test
    void allowlistEnforcementPassesAgainstADriverThatHonorsTheDeclaredFields() throws Throwable {
        HubSearchContractFixture fixture = fixture(new FakeHubSearchContractDriver());
        runEvery(containerNamed(ProviderContractHarness.hubLane(fixture), "ALLOWLIST_ENFORCEMENT"));
    }

    @Test
    void scopeChangeIsEffectiveOnTheVeryNextQuery() throws Throwable {
        HubSearchContractFixture fixture = fixture(new FakeHubSearchContractDriver());
        runEvery(containerNamed(ProviderContractHarness.hubLane(fixture), "SCOPE_CHANGE_EFFECTIVE_NEXT_QUERY"));
    }

    @Test
    void fakeDriverItselfHonorsUnscopedUsersSeeingEverything() {
        FakeHubSearchContractDriver driver = new FakeHubSearchContractDriver();
        TenantHandle tenant = driver.provisionTenant();
        UserHandle unscoped = driver.provisionUser(tenant, Set.of());
        CanonicalEntityDef entity = new CanonicalEntityDef("probe_entity",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.applySearchIndex(tenant, "contract-suite-hub-isolation", entity, entity.columns());
        driver.ingestCanonicalRows(tenant, entity, List.of(new CanonicalRow("u-1", Map.of("probe", "marker"))));

        assertThat(driver.query(tenant, unscoped, "marker", Map.of()).hits()).isNotEmpty();
    }
}
