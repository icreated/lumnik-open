package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * The wiring {@link SearchResourceScopeTest} cannot see: what the resource actually hands the
 * provider. The gate is stubbed to answer an empty success and to capture the query, so a scoped
 * request that silently became a tenant-wide one fails here.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class SearchScopeWiringIT {

    @InjectMock SearchGate gate;
    @InjectMock EntityRegistry registry;

    private static EntityManifest entity(String name, String... scopes) {
        return new EntityManifest("Entity", name, null, List.of("code"), List.of(), List.of(scopes), null, null, null);
    }

    @BeforeEach
    void stub() {
        List<EntityManifest> all = List.of(entity("Client", "clients"), entity("Article", "stock"),
            entity("Livraison", "stock", "clients"));
        Mockito.when(registry.all()).thenReturn(all);
        all.forEach(m -> Mockito.when(registry.find(m.name())).thenReturn(m));
        Mockito.when(gate.search(Mockito.any())).thenReturn(new SearchOutcome.Hits(IndexedSearchResult.empty()));
    }

    private static RequestSpecification as(String... roles) {
        return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of(roles), null));
    }

    private Set<String> asked() {
        ArgumentCaptor<IndexedSearchQuery> q = ArgumentCaptor.forClass(IndexedSearchQuery.class);
        Mockito.verify(gate).search(q.capture());
        return q.getValue().allowedEntities();
    }

    @Test
    void without_scope_the_provider_gets_everything_the_caller_may_read() {
        as("lm_user", "scope:clients", "scope:stock").queryParam("q", "x")
            .when().get("/api/search").then().statusCode(200);
        assertThat(asked()).containsExactlyInAnyOrder("Client", "Article", "Livraison");
    }

    @Test
    void with_scope_the_provider_gets_only_the_apps_metier() {
        as("lm_user", "scope:clients", "scope:stock").queryParam("q", "x").queryParam("scope", "stock")
            .when().get("/api/search").then().statusCode(200);
        assertThat(asked()).containsExactlyInAnyOrder("Article", "Livraison");
    }

    @Test
    void an_unscoped_caller_naming_a_scope_is_narrowed_too() {
        as("lm_user").queryParam("q", "x").queryParam("scope", "clients")
            .when().get("/api/search").then().statusCode(200);
        assertThat(asked()).containsExactlyInAnyOrder("Client", "Livraison");
    }

    @Test
    void an_entity_inside_the_scope_is_searched_alone() {
        as("lm_user").queryParam("q", "x").queryParam("scope", "stock").queryParam("entity", "Article")
            .when().get("/api/search").then().statusCode(200);
        assertThat(asked()).containsExactly("Article");
    }

    @Test
    void an_entity_outside_the_scope_is_the_uniform_403_and_the_provider_is_never_asked() {
        as("lm_user").queryParam("q", "x").queryParam("scope", "stock").queryParam("entity", "Client")
            .when().get("/api/search").then().statusCode(403).body("error", equalTo("scope not granted"));
        Mockito.verify(gate, Mockito.never()).search(Mockito.any());
    }

    @Test
    void a_repeated_scope_is_refused_not_first_wins() {
        as("lm_user").queryParam("q", "x").queryParam("scope", "stock", "clients")
            .when().get("/api/search").then().statusCode(400);
        Mockito.verify(gate, Mockito.never()).search(Mockito.any());
    }

    @Test
    void surrounding_whitespace_is_not_trimmed_into_a_valid_scope() {
        as("lm_user").queryParam("q", "x").queryParam("scope", " stock ")
            .when().get("/api/search").then().statusCode(400);
    }
}
