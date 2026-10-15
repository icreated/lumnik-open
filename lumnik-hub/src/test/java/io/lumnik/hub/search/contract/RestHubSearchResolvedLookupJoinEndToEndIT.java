package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.search.mark.SearchProjectionReconciler;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.search.contract.CanonicalSearchHit;
import io.lumnik.testkit.search.contract.TenantHandle;
import io.lumnik.testkit.search.contract.UserHandle;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #850 acceptance, as a client of {@code /api/search} sees it, against the REAL PostgreSQL provider (the {@code
 * -Psearch} lane): on a RESOLVED entity, a lookup row that arrives after the documents were indexed populates the
 * joined field; a second row of different content for the same key makes it ambiguous and the field must be GONE
 * from the indexed document.
 */
@QuarkusTest
@TestProfile(SearchBackendOidcProfile.class)
@EnabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class RestHubSearchResolvedLookupJoinEndToEndIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    @Inject SearchProjectionWorker worker;
    @Inject SearchProjectionReconciler reconciler;

    private RestHubSearchContractDriver driver;
    private TenantHandle tenant;
    private UserHandle user;

    @Test
    void a_lookup_row_populates_the_joined_field_of_a_resolved_document_and_an_ambiguous_key_removes_it() throws Exception {
        driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        tenant = driver.provisionTenant();
        user = driver.provisionUser(tenant, Set.of("scope:lk"));
        long t = tenant.tenantId();
        String n = String.valueOf(System.nanoTime());
        String products = "t_e2er_p_" + n, lookup = "t_e2er_c_" + n, entity = "E2eResolved" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        apply("/api/entities/apply", resolutionJoinEntityYaml(entity, products, lookup, "category_name"));
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (" + t + ", 'P1', 'C1', 'alpha')");
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (" + t + ", 'P2', 'C2', 'bravo')");
        insertIdentity(adminDs, t, entity, products, "P1", UUID.randomUUID());   // decided: a document
        insertIdentity(adminDs, t, entity, products, "P2", UUID.randomUUID());
        // the index's rebuild projects the decided rows; nothing here produces a mark for the crosswalk itself
        apply("/api/search-indexes/apply", searchIndex("e2e-resolved-" + n, entity, "product_name", "category_name"));

        // 0. documents indexed, no lookup row yet: the joined field is empty
        awaitCategory("alpha", null);

        // 1. the first lookup row for C1: the joined value reaches the index, only for the entity holding C1
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (" + t + ", 'C1', 'Pumps')");
        awaitCategory("alpha", "Pumps");
        assertThat(category("bravo")).as("C2 has no lookup row: untouched").isNull();

        // 2. a _row_hash conflict (the byte-identical row ingested again) only moves _ingested_at: nothing changes
        exec(adminDs, "UPDATE connector." + lookup + " SET _ingested_at = now() WHERE tenant_id = " + t);
        converge();
        assertThat(category("alpha")).isEqualTo("Pumps");

        // 3. one more row of DIFFERENT content for C1: ambiguous — the indexed document must lose the value
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (" + t + ", 'C1', 'Pompes')");
        awaitCategory("alpha", null);
        assertThat(driver.query(tenant, user, "Pumps", Map.of()).hits())
            .as("the stale value is not findable either").extracting(CanonicalSearchHit::fields)
            .noneMatch(f -> "Pumps".equals(f.get("category_name")));
    }

    private void apply(String path, String yaml) {
        RestAssured.given().config(YamlBody.CONFIG)
            .header(RestHubSearchContractDriver.bearer(driver.integratorToken(tenant)))
            .contentType("application/yaml").body(yaml).post(path).then().log().ifValidationFails().statusCode(200);
    }

    /** What the hub's own tick would do over a few seconds: scan the tables, drain the marks. */
    private void converge() throws Exception {
        for (int i = 0; i < 4; i++) {
            reconciler.sweep();
            worker.discoveryTick();
            Thread.sleep(250);
        }
    }

    private String category(String productName) {
        Optional<CanonicalSearchHit> hit = driver.query(tenant, user, productName, Map.of()).hits().stream()
            .filter(h -> productName.equals(h.fields().get("product_name"))).findFirst();
        assertThat(hit).as("the product '" + productName + "' is in the index").isPresent();
        return hit.get().fields().get("category_name");
    }

    private void awaitCategory(String productName, String expected) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        String last = "<no hit>";
        while (System.nanoTime() < deadline) {
            converge();
            Optional<CanonicalSearchHit> hit = driver.query(tenant, user, productName, Map.of()).hits().stream()
                .filter(h -> productName.equals(h.fields().get("product_name"))).findFirst();
            if (hit.isPresent()) {
                last = String.valueOf(hit.get().fields().get("category_name"));
                if (Objects.equals(hit.get().fields().get("category_name"), expected)) return;
            }
        }
        throw new AssertionError("category_name of '" + productName + "' never became " + expected + " (last: " + last + ")");
    }
}
