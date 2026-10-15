package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.testkit.search.contract.CanonicalEntityDef;
import io.lumnik.testkit.search.contract.CanonicalFieldDef;
import io.lumnik.testkit.search.contract.CanonicalRow;
import io.lumnik.testkit.search.contract.CanonicalSearchResult;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * #674 — the hub-owned link-resolution property, proved outside {@code ProviderContractHarness}
 * entirely (design spec §2.2): a search hit's {@code canonicalLink} is a plain, relative {@code
 * /api/entities/{name}?...} path ({@code SearchResource#canonicalLink}) that any authenticated
 * caller can follow through {@code EntityReadResource} — a plain, always-on {@code lm_user} door,
 * no part of the Search machinery at all. Confirmed against that class directly during planning:
 * it has no per-entity policy of its own, relying entirely on the request's {@code
 * app.current_tenant} GUC plus the security_invoker view, so a cross-tenant follow is NOT refused
 * with 403/404 — it is a plain {@code 200} with an RLS-filtered, empty body. And that body is a
 * bare JSON array, never {@code {"rows": [...]}} — confirmed against {@code EntityReadResource}'s
 * own {@code Response.ok(rows).build()} and against every other passing IT over that same
 * resource ({@code EntityReadIT}, {@code EntityFusionRlsIT}), which all assert on {@code
 * body("size()", ...)} / {@code body("[0]....", ...)} at the response root, never a wrapper key.
 *
 * <p>{@link SearchProviderContractRlsProfile} rather than the {@code NoBypassOidcProfile} auth
 * every sibling driver IT in this package uses (Tasks 11-13): this is the first test in *this*
 * package whose own assertion IS a genuine cross-tenant RLS property, and {@code
 * RlsEnforcedProfile}'s own javadoc (io.lumnik.hub.connector.rls) is explicit that the plain
 * {@code %test} DevServices Postgres connects as a BYPASSRLS-equivalent owner — without real RLS
 * enforcement wired in, tenant B's read would see tenant A's row too, and the very assertion this
 * test exists to make would fail for the wrong reason. This profile already composes that
 * enforcement with the real-JWT auth {@link RestHubSearchContractDriver} needs, exactly as {@link
 * SearchProviderContractIT} does for the same combined need — and it is why this class, like that
 * one, only runs under {@code -Psearch} ({@code lumnik.test.searchContractSuiteActive}): outside
 * it, no real {@code IndexedSearchPort} is wired and the {@code canonicalLink} this test follows
 * would never be produced.
 */
@QuarkusTest
@TestProfile(SearchProviderContractRlsProfile.class)
@EnabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class CanonicalLinkAuthorizationIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    @Inject SearchProjectionWorker worker;

    @Test
    void aHitsCanonicalLinkFollowedUnderADifferentTenantReturnsNoRows() {
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenantA = driver.provisionTenant();
        TenantHandle tenantB = driver.provisionTenant();
        UserHandle userA = driver.provisionUser(tenantA, Set.of());
        driver.provisionUser(tenantB, Set.of());

        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task16_link",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.applySearchIndex(tenantA, "contract-suite-task16-index", entity, entity.columns());
        driver.ingestCanonicalRows(tenantA, entity, List.of(new CanonicalRow("l16-1", Map.of("probe", "findme"))));
        driver.awaitProjectionConvergence(tenantA, "contract-suite-task16-index", Duration.ofSeconds(10));

        CanonicalSearchResult result = driver.query(tenantA, userA, "findme", Map.of());
        assertThat(result.hits()).isNotEmpty();
        String canonicalLink = result.hits().get(0).canonicalLink();
        assertThat(canonicalLink).isNotBlank();

        // Owning tenant: the row it just ingested comes back — a bare, non-empty JSON array.
        RestAssured.given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_user"), null, tenantA.tenantId()))
            .get(canonicalLink)
            .then().statusCode(200)
            .body("size()", greaterThan(0));

        // A different tenant following the SAME link: 200, never 403/404 — EntityReadResource has
        // no per-entity policy, only RLS — and RLS filters it down to a bare, empty JSON array.
        RestAssured.given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_user"), null, tenantB.tenantId()))
            .get(canonicalLink)
            .then().statusCode(200)
            .body("size()", equalTo(0));
    }
}
