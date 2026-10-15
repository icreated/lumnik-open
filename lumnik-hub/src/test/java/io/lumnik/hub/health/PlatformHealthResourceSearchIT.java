package io.lumnik.hub.health;

import io.lumnik.hub.search.SearchCapability;
import io.lumnik.hub.search.SearchGate;
import io.lumnik.extraction.spi.search.SearchAvailability;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #703 health scope: a Search outage — whether nothing is installed or an installed provider is
 * degraded — never affects root {@code platform} readiness.
 */
@QuarkusTest
class PlatformHealthResourceSearchIT {

    @Inject SearchGate realSearchGate;

    /** Always undoes any {@code SearchGate} mock this class installed — {@code SearchOperationsResourceIT}'s
     *  own idiom: restoring the real, CDI-managed singleton is always safe, mocked or not. */
    @AfterEach
    void restoreRealSearchGate() {
        QuarkusMock.installMockForType(realSearchGate, SearchGate.class);
    }

    @Test
    // PR #734 review finding: this asserts the no-provider posture itself, so it cannot hold under
    // -Psearch, which installs lumnik-search-pg on the runtime classpath — a provider genuinely IS
    // available there, contradicting this test's own premise. Skipped, not weakened: the assertion
    // stays a real "not_installed" claim rather than being loosened to tolerate either state.
    @DisabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
    void searchUnavailableNeverAffectsRootPlatformStatus() {
        // No provider installed in this @QuarkusTest profile (the default, open-edition posture).
        given().get("/api/platform/health").then()
            .statusCode(200)
            .body("platform", equalTo("ok"))
            .body("search.status", equalTo("not_installed"))
            .body("search.affectsReadiness", equalTo(false));
    }

    @Test
    void searchDegradedNeverAffectsRootPlatformStatus() {
        // SearchGate has no background consumer (unlike ProjectionSinkGate, consumed by the
        // scheduler/reaper) — only request-driven resources inject it — so mocking it here for the
        // duration of this one test is safe: nothing else observes it between requests.
        SearchGate mockGate = mock(SearchGate.class);
        when(mockGate.capability())
            .thenReturn(new SearchCapability(SearchAvailability.DEGRADED, "opensearch", "backend_unreachable", null));
        QuarkusMock.installMockForType(mockGate, SearchGate.class);

        given().get("/api/platform/health").then()
            .statusCode(200)
            .body("platform", equalTo("ok"))
            .body("search.status", equalTo("degraded"))
            .body("search.affectsReadiness", equalTo(false))
            .body("search.error", notNullValue());
    }
}
