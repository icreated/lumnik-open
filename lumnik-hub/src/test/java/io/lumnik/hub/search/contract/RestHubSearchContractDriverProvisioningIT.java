package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(SearchBackendOidcProfile.class)
@EnabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class RestHubSearchContractDriverProvisioningIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    @Inject SearchProjectionWorker worker;

    @Test
    void provisioningAndIngestionWorkAgainstRealHubMachinery() {
        RestHubSearchContractDriver driver =
            new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);

        TenantHandle tenant = driver.provisionTenant();
        UserHandle user = driver.provisionUser(tenant, Set.of());
        assertThat(tenant.tenantId()).isPositive();
        assertThat(user.tenantId()).isEqualTo(tenant.tenantId());

        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task11_probe",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.applySearchIndex(tenant, "contract-suite-task11-index", entity,
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.ingestCanonicalRows(tenant, entity, List.of(new CanonicalRow("t11-1", Map.of("probe", "marker"))));
        driver.awaitProjectionConvergence(tenant, "contract-suite-task11-index", Duration.ofSeconds(10));

        // Full end-to-end proof (querying it back through /api/search) is Task 12 — this step only
        // proves ingestion reached the hub without throwing, so the two concerns stay independently
        // reviewable.
    }
}
