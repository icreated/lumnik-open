package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.testkit.search.contract.HubSearchContractFixture;
import io.lumnik.testkit.search.contract.ProviderContractHarness;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.stream.Stream;

/** #674: the hub-lane contract suite, run once per JVM against the single provider
 *  {@code lumnik.search.provider} names for it (the execution matrix, §2.2 of the design spec — a
 *  second provider is a Maven invocation with a different value, never a second CDI-discovered
 *  provider in this same run). Skipped entirely outside {@code -Psearch}. */
@QuarkusTest
@TestProfile(SearchProviderContractRlsProfile.class)
@EnabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class SearchProviderContractIT {

    @Inject Instance<IndexedSearchPort> providers;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    @Inject SearchProjectionWorker worker;

    @ConfigProperty(name = "lumnik.search.provider")
    String configuredProvider;

    @TestFactory
    Stream<DynamicNode> hubContract() {
        ConfiguredProviderResolver.Resolved resolved = ConfiguredProviderResolver.resolve(providers, configuredProvider);
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        HubSearchContractFixture fixture = new HubSearchContractFixture(resolved.port(), resolved.sink(), driver);
        return ProviderContractHarness.hubLane(fixture);
    }
}
