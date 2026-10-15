package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.SearchAvailability;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.util.List;

import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.alwaysRequired;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.containerNamed;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.runEvery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderContractHarnessOutageAndLifecycleTest {

    private static ProviderCapabilities bootAbsentNotApplicable() {
        return dimension -> dimension == ProviderLaneDimension.BOOT_ABSENT
            ? new Applicability.NotApplicable("this stub is always reachable at boot, by construction")
            : new Applicability.Required();
    }

    @Test
    void lifecycleCutoverAtomicityNeverObservesAHalfPublishedGeneration() throws Throwable {
        StubSearchProvider provider = new StubSearchProvider();
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), provider.asFaultController(), List.of());

        DynamicContainer lifecycle = containerNamed(ProviderContractHarness.providerLane(fixture), "LIFECYCLE_CUTOVER");
        runEvery(lifecycle);
    }

    @Test
    void bootAbsentAbortsWhenNotApplicableAndNeverCallsTheHook() {
        StubSearchProvider provider = new StubSearchProvider();
        ProviderFaultController failIfCalled = new ProviderFaultController() {
            public AutoCloseable loseBackendDuringQuery() { throw new UnsupportedOperationException(); }
            public AutoCloseable loseBackendDuringIndexing() { throw new UnsupportedOperationException(); }
            public IndexedSearchPort bootWithBackendAbsent() {
                throw new AssertionError("must never be called for a NotApplicable dimension");
            }
        };
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, bootAbsentNotApplicable(), failIfCalled, List.of());

        DynamicContainer bootAbsent = containerNamed(ProviderContractHarness.providerLane(fixture), "BOOT_ABSENT");
        DynamicTest onlyTest = (DynamicTest) bootAbsent.getChildren().toList().get(0);
        assertThatThrownBy(() -> onlyTest.getExecutable().execute()).isInstanceOf(TestAbortedException.class);
    }

    @Test
    void bootAbsentRunsTheRealHookWhenRequired() throws Throwable {
        StubSearchProvider provider = new StubSearchProvider();
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), provider.asFaultController(), List.of());

        DynamicContainer bootAbsent = containerNamed(ProviderContractHarness.providerLane(fixture), "BOOT_ABSENT");
        runEvery(bootAbsent);
    }

    @Test
    void backendLostDuringQueryThrowsIndexedSearchExceptionThenRecovers() throws Throwable {
        StubSearchProvider provider = new StubSearchProvider();
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), provider.asFaultController(), List.of());

        DynamicContainer outage = containerNamed(
            ProviderContractHarness.providerLane(fixture), "BACKEND_LOST_DURING_QUERY");
        runEvery(outage);
        assertThat(provider.status().state()).isEqualTo(SearchAvailability.AVAILABLE);
    }

    @Test
    void backendLostDuringIndexingThrowsIllegalStateExceptionThenRecovers() throws Throwable {
        StubSearchProvider provider = new StubSearchProvider();
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), provider.asFaultController(), List.of());

        DynamicContainer outage = containerNamed(
            ProviderContractHarness.providerLane(fixture), "BACKEND_LOST_DURING_INDEXING");
        runEvery(outage);
        assertThat(provider.status().state()).isEqualTo(SearchAvailability.AVAILABLE);
    }
}
