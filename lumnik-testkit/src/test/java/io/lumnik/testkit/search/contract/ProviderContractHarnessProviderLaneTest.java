package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchException;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.alwaysRequired;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.containerNamed;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.refusingFaultController;
import static io.lumnik.testkit.search.contract.ContractHarnessTestSupport.runEvery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderContractHarnessProviderLaneTest {

    @Test
    void isolationPassesAgainstAProviderThatActuallyIsolatesTenants() throws Throwable {
        StubSearchProvider provider = new StubSearchProvider();
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), refusingFaultController(), List.of());

        DynamicContainer isolation = containerNamed(ProviderContractHarness.providerLane(fixture), "ISOLATION");
        runEvery(isolation);
    }

    @Test
    void isolationFailsAgainstAProviderThatLeaksAcrossTenants() {
        StubSearchProvider provider = new StubSearchProvider();
        provider.leakAcrossTenants = true;
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), refusingFaultController(), List.of());

        DynamicContainer isolation = containerNamed(ProviderContractHarness.providerLane(fixture), "ISOLATION");
        Executable first = ((DynamicTest) isolation.getChildren().toList().get(0)).getExecutable();
        assertThatThrownBy(first::execute).isInstanceOf(AssertionError.class);
    }

    @Test
    void queryAbuseRunsTheFixturesProviderNativeCorpusWithoutThrowing() throws Throwable {
        StubSearchProvider provider = new StubSearchProvider();
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), refusingFaultController(),
            List.of("Acme & (evil | injection) : \"quote\""));

        DynamicContainer queryAbuse = containerNamed(ProviderContractHarness.providerLane(fixture), "QUERY_ABUSE");
        // DynamicContainer.getChildren() always returns the same Stream instance (it is stored as a
        // field, never re-wrapped), so it can only be drained once — captured here instead of calling
        // getChildren() a second time after runEvery() already consumed it.
        List<? extends DynamicNode> children = queryAbuse.getChildren().toList();
        for (DynamicNode child : children) {
            ((DynamicTest) child).getExecutable().execute();
        }
        assertThat(children).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void queryAbuseFailsWhenTheProviderThrowsAGenuineIndexedSearchException() {
        // PR #734 review finding: assertHostileQueryNeverMatchesIndiscriminately used to swallow the
        // base IndexedSearchException, not just its InvalidIndexedSearchQueryException subclass — a
        // real provider crash on hostile input would have passed silently. StubSearchProvider's
        // failSearch flag throws the base (non-Invalid) type, exactly the crash this dimension must
        // not tolerate; a hung fix here must fail loudly, not swallow it.
        StubSearchProvider provider = new StubSearchProvider();
        provider.failSearch = true;
        ProviderContractFixture fixture = new ProviderContractFixture(
            provider, provider, alwaysRequired(), refusingFaultController(), List.of());

        DynamicContainer queryAbuse = containerNamed(ProviderContractHarness.providerLane(fixture), "QUERY_ABUSE");
        List<? extends DynamicNode> children = queryAbuse.getChildren().toList();
        assertThat(children).isNotEmpty();
        for (DynamicNode child : children) {
            Executable exec = ((DynamicTest) child).getExecutable();
            assertThatThrownBy(exec::execute).isInstanceOf(IndexedSearchException.class);
        }
    }
}
