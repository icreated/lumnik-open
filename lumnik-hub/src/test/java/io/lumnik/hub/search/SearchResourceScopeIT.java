package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #673's scope refusal under real bearer JWTs (dev-bypass off) — {@code SearchResourceIT}'s
 * dev-bypass identity is always unscoped (tenant-wide), so it cannot drive a genuinely bound
 * caller through {@code ScopeGrants.allowsAny}. Mirrors {@code SearchIndexScopeIT}/{@code
 * SearchOperationsScopeIT}'s fixture idiom exactly: an entity scoped {@code [sales]}, a caller
 * holding only {@code [magasin]}.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class SearchResourceScopeIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SearchGate realSearchGate;

    private static String bearer(String... roles) {
        return "Bearer " + NoBypassOidcProfile.token(Set.of(roles), null);
    }

    private static String admin() { return bearer("lm_admin", "lm_integrator", "lm_user"); }
    private static String userMagasin() { return bearer("lm_user", "scope:magasin"); }

    private static RequestSpecification asYaml(String token) {
        return given().config(YamlBody.CONFIG).header("Authorization", token).contentType("application/yaml");
    }

    private static RequestSpecification as(String token) {
        return given().header("Authorization", token);
    }

    @AfterEach
    void restoreRealGate() {
        QuarkusMock.installMockForType(realSearchGate, SearchGate.class);
    }

    /** A fresh entity scoped {@code [sales]} only — never in {@code userMagasin()}'s grants. */
    private String applyEntityScopedSales() throws Exception {
        String name = "search673scope" + System.nanoTime();
        asYaml(admin()).body(entity(adminDs, name, "t_" + name, List.of("sales"), "code"))
            .post("/api/entities/apply").then().statusCode(200);
        return name;
    }

    @Test
    void aCallerWhoseGrantsExcludeTheEntityIsRefusedOnTheEntityFilter() throws Exception {
        String name = applyEntityScopedSales();
        SearchGate g = mock(SearchGate.class);
        QuarkusMock.installMockForType(g, SearchGate.class);

        as(userMagasin()).get("/api/search?q=acme&entity=" + name)
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));

        // Refused before ever asking the provider — an out-of-scope ?entity= must never reach it.
        verifyNoInteractions(g);
    }

    @Test
    void aCallerWhoseGrantsExcludeTheEntityGetsItDroppedFromTheCrossEntitySearchWithoutA403() throws Exception {
        String name = applyEntityScopedSales();
        SearchGate g = mock(SearchGate.class);
        when(g.search(any())).thenReturn(new SearchOutcome.Hits(IndexedSearchResult.empty()));
        QuarkusMock.installMockForType(g, SearchGate.class);

        // No ?entity= filter: the caller may still search everything they DO hold — an entity
        // outside their grants is silently excluded from the allowed set, not a refusal.
        as(userMagasin()).get("/api/search?q=acme").then().statusCode(200);

        ArgumentCaptor<IndexedSearchQuery> captor = ArgumentCaptor.forClass(IndexedSearchQuery.class);
        verify(g).search(captor.capture());
        assertThat(captor.getValue().allowedEntities()).doesNotContain(name);
    }
}
