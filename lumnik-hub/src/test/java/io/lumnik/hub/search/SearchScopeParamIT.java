package io.lumnik.hub.search;

import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * {@code GET /api/search?scope=} under real bearer JWTs: the app's scope narrows the search, and a
 * malformed or ungranted scope is refused BEFORE the provider is asked — so these cases hold on a hub
 * with no Search provider at all (the allowed-set arithmetic itself is {@link SearchResourceScopeTest}).
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class SearchScopeParamIT {

    private static RequestSpecification as(String... roles) {
        return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of(roles), null));
    }

    @Test
    void an_empty_scope_is_a_400() {
        as("lm_user").queryParam("q", "x").queryParam("scope", "")
            .when().get("/api/search").then().statusCode(400);
    }

    @Test
    void a_malformed_scope_is_a_400() {
        as("lm_user").queryParam("q", "x").queryParam("scope", "Bad Scope!")
            .when().get("/api/search").then().statusCode(400);
    }

    @Test
    void a_bound_caller_without_the_scope_is_refused_with_the_uniform_403() {
        as("lm_user", "scope:clients").queryParam("q", "x").queryParam("scope", "stock")
            .when().get("/api/search").then().statusCode(403).body("error", equalTo("scope not granted"));
    }

    @Test
    void a_bound_caller_holding_the_scope_gets_past_the_refusals() {
        as("lm_user", "scope:clients", "scope:stock").queryParam("q", "x").queryParam("scope", "stock")
            .when().get("/api/search").then().statusCode(not(anyOf(equalTo(400), equalTo(403))));
    }

    @Test
    void an_unscoped_caller_is_allowed_to_name_a_scope() {
        as("lm_user").queryParam("q", "x").queryParam("scope", "stock")
            .when().get("/api/search").then().statusCode(not(anyOf(equalTo(400), equalTo(403))));
    }

    @Test
    void without_the_parameter_nothing_changes() {
        as("lm_user", "scope:clients").queryParam("q", "x")
            .when().get("/api/search").then().statusCode(not(anyOf(equalTo(400), equalTo(403))));
    }

    @Test
    void the_historical_validations_keep_their_priority() {
        // q is checked before scope: a blank q with a bad scope is still the q error
        as("lm_user").queryParam("q", " ").queryParam("scope", "Bad Scope!")
            .when().get("/api/search").then().statusCode(400).body("error", equalTo("q must be 1-200 characters"));
    }
}
