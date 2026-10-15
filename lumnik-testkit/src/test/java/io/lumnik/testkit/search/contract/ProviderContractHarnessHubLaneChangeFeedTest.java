package io.lumnik.testkit.search.contract;

import org.junit.jupiter.api.Test;

import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.containerNamed;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.fixture;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.runEvery;

class ProviderContractHarnessHubLaneChangeFeedTest {

    @Test
    void changeFeedTriggersConvergeWithinOneCycle() throws Throwable {
        HubSearchContractFixture fixture = fixture(new FakeHubSearchContractDriver());
        runEvery(containerNamed(ProviderContractHarness.hubLane(fixture), "CHANGE_FEED_TRIGGERS"));
    }

    @Test
    void rebuildWhileReadsContinueNeverBlocksAQuery() throws Throwable {
        HubSearchContractFixture fixture = fixture(new FakeHubSearchContractDriver());
        runEvery(containerNamed(ProviderContractHarness.hubLane(fixture), "REBUILD_WHILE_READS_CONTINUE"));
    }

    @Test
    void staleCanonicalRowsDisappearWithoutAFullRebuild() throws Throwable {
        HubSearchContractFixture fixture = fixture(new FakeHubSearchContractDriver());
        runEvery(containerNamed(ProviderContractHarness.hubLane(fixture), "STALE_CANONICAL_ROWS"));
    }
}
