package io.lumnik.hub.auth;

import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;

/**
 * The app registry's doors — read AND write — under a REAL token. This design was first written
 * with GET /api/apps public and unauthenticated; the premise did not survive contact with
 * TenantContextFilter, which aborts 401 on any request without a tenant_id claim long before a
 * resource is reached. The endpoint is lm_user-gated instead — and that is only checkable here,
 * because %test runs dev-bypass=true and an auth assertion in the default profile proves nothing
 * at all.
 *
 * <p>The same is true of what the apply door decides about WHO the caller is: under dev-bypass
 * every caller holds every role and no scope at all, so the lm_integrator gate and the refusal on
 * the demoted default can only be pinned from here. The door's other two scope refusals — on the
 * declared scope and on the overwritten row — are covered differently: ScopeBoundaryInventoryTest
 * sweeps the source to prove the wiring, and ScopeRefusalOnTheWireIT measures the throw-to-response
 * conversion on a different apply door, a framework fact true or false for all of them together.
 * AppApplyResourceIT is about validation; this file is about authorization.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class AppRegistryAuthIT {

    @Test
    void anAnonymousReadIsRefused() {
        given().when().get("/api/apps").then().statusCode(401);
    }

    @Test
    void anUnscopedUserSeesEveryDeclaredApp() {
        given().auth().oauth2(NoBypassOidcProfile.token("lm_user"))
            .when().get("/api/apps")
            .then().statusCode(200)
            .body("name", hasItems("clients", "stock"));
    }

    @Test
    void aBoundUserSeesOnlyTheAppsInTheirScope() {
        given().auth().oauth2(NoBypassOidcProfile.token(Set.of("lm_user", "scope:stock"), null))
            .when().get("/api/apps")
            .then().statusCode(200)
            .body("$", hasSize(1))
            .body("[0].name", equalTo("stock"));
    }

    @Test
    void anAppOutsideTheCallersScopeRefusesInTheFrozenWords() {
        given().auth().oauth2(NoBypassOidcProfile.token(Set.of("lm_user", "scope:stock"), null))
            .when().get("/api/apps/clients")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));
    }

    private static RequestSpecification apply(Set<String> roles) {
        return given().config(YamlBody.CONFIG).auth().oauth2(NoBypassOidcProfile.token(roles, null))
            .contentType("application/yaml");
    }

    /** The write door's role gate. An lm_user may READ the registry; declaring is an integrator's
     *  gesture, and nothing else in the tree asserts that — under dev-bypass every caller holds
     *  every role, so an assertion written in AppApplyResourceIT would pass whatever the gate did. */
    @Test
    void aReaderMayNotDeclareAnApp() {
        apply(Set.of("lm_user")).body(manifest("salon-auth", "salon", ""))
            .when().post("/api/apps/apply")
            .then().statusCode(403);
    }

    /**
     * Taking the default flag is a WRITE to whoever holds it. An integrator bound to one scope
     * would otherwise demote an app in a scope they cannot even read — and learn its name from
     * {@code demotedDefault} on the way. The refusal is the frozen one, and the registry is
     * unmoved afterwards: the deployment-wide landing app is not something a bound caller takes.
     */
    @Test
    void takingTheDefaultFromAnotherMetierIsRefusedInTheFrozenWords() {
        apply(Set.of("lm_integrator", "scope:stock"))
            .body(manifest("stock-vitrine", "stock", "  default: true\n"))
            .when().post("/api/apps/apply")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));

        given().auth().oauth2(NoBypassOidcProfile.token("lm_user"))
            .when().get("/api/apps/clients")
            .then().statusCode(200)
            .body("default", equalTo(true));
    }

    /** The delete door's role gate, {@link #aReaderMayNotDeclareAnApp}'s twin. */
    @Test
    void aReaderMayNotDeleteAnApp() {
        given().auth().oauth2(NoBypassOidcProfile.token("lm_user"))
            .when().delete("/api/apps/stock")
            .then().statusCode(403);
    }

    /**
     * Deleting an app in a scope the caller does not hold must not tell them whether the name
     * exists at all — the same frozen refusal {@link #anAppOutsideTheCallersScopeRefusesInTheFrozenWords}
     * proves for the read door.
     */
    @Test
    void deletingAnAppOutsideTheCallersScopeRefusesInTheFrozenWords() {
        given().auth().oauth2(NoBypassOidcProfile.token(Set.of("lm_integrator", "scope:stock"), null))
            .when().delete("/api/apps/clients")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));

        given().auth().oauth2(NoBypassOidcProfile.token("lm_user"))
            .when().get("/api/apps/clients")
            .then().statusCode(200);
    }
}
