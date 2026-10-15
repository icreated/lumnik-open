package io.lumnik.testkit.search.contract;

import io.lumnik.extraction.spi.search.IndexedSearchPort;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;

import java.util.stream.Stream;

/** Shared plumbing for {@code ProviderContractHarness}'s own unit tests — walking the
 *  {@code Stream<DynamicNode>} it returns and building the doubles every test class needs. Extracted
 *  because four sibling test classes were each redefining these identically. */
final class ContractHarnessTestSupport {

    private ContractHarnessTestSupport() {}

    static DynamicContainer containerNamed(Stream<DynamicNode> nodes, String name) {
        return nodes.filter(DynamicContainer.class::isInstance)
            .map(DynamicContainer.class::cast)
            .filter(c -> c.getDisplayName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no container named " + name));
    }

    static void runEvery(DynamicContainer container) throws Throwable {
        for (DynamicNode child : container.getChildren().toList()) {
            ((DynamicTest) child).getExecutable().execute();
        }
    }

    static HubSearchContractFixture fixture(HubSearchContractDriver driver) {
        StubSearchProvider providerAndSink = new StubSearchProvider();
        return new HubSearchContractFixture(providerAndSink, providerAndSink, driver);
    }

    static ProviderCapabilities alwaysRequired() {
        return dimension -> new Applicability.Required();
    }

    /** A fault controller whose methods are never expected to be called — any call is a test bug. */
    static ProviderFaultController refusingFaultController() {
        return new ProviderFaultController() {
            public AutoCloseable loseBackendDuringQuery() { throw new UnsupportedOperationException(); }
            public AutoCloseable loseBackendDuringIndexing() { throw new UnsupportedOperationException(); }
            public IndexedSearchPort bootWithBackendAbsent() { throw new UnsupportedOperationException(); }
        };
    }
}
