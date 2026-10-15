package io.lumnik.hub.api.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.lumnik.hub.api.search.SearchOperationsItSupport.entity;
import static io.lumnik.hub.api.search.SearchOperationsItSupport.freeEntities;
import static io.lumnik.hub.api.search.SearchOperationsItSupport.searchIndex;
import static io.lumnik.hub.api.search.SearchOperationsItSupport.unique;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * #703's operations door under real bearer JWTs (dev-bypass off) — the genuine {@code scope not
 * granted} 403 {@code SearchIndexScopeIT} already proves for the #669 apply/list/delete doors, here
 * for status/rebuild/cancel. Mirrors {@code SearchIndexScopeIT}'s own fixture idioms exactly
 * (entity scoped {@code [sales]}, a caller holding only {@code [magasin]}).
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class SearchOperationsScopeIT {

    private static final String ENTITY = "SearchOpsScope703";
    private static final String TABLE = "t_searchopsscope703_erp";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static String bearer(String... roles) {
        return "Bearer " + NoBypassOidcProfile.token(Set.of(roles), null);
    }

    private static String admin() { return bearer("lm_admin", "lm_integrator", "lm_user"); }
    private static String integratorMagasin() { return bearer("lm_integrator", "lm_user", "scope:magasin"); }

    private static RequestSpecification as(String token) {
        return given().header("Authorization", token);
    }

    private static RequestSpecification asYaml(String token) {
        return given().config(YamlBody.CONFIG).header("Authorization", token).contentType("application/yaml");
    }

    @BeforeEach
    void entityAndIndex() throws Exception {
        freeEntities(adminDs, ENTITY);
        asYaml(admin()).body(entity(adminDs, ENTITY, TABLE))
            .post("/api/entities/apply").then().statusCode(200);
    }

    private String applyIndexAsAdmin() {
        String name = unique("scoped");
        asYaml(admin()).body(searchIndex(name, ENTITY))
            .post("/api/search-indexes/apply").then().statusCode(200);
        return name;
    }

    @Test
    void aCallerWhoseGrantsExcludeTheIndexsEntityIsRefusedOnStatus() {
        String name = applyIndexAsAdmin();
        as(integratorMagasin())
            .get("/api/platform/search/index/" + name + "/status")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));
    }

    @Test
    void aCallerWhoseGrantsExcludeTheIndexsEntityIsRefusedOnRebuild() {
        String name = applyIndexAsAdmin();
        as(integratorMagasin())
            .post("/api/platform/search/index/" + name + "/rebuild")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));
    }

    @Test
    void aCallerWhoseGrantsExcludeTheIndexsEntityIsRefusedOnCancel() {
        String name = applyIndexAsAdmin();
        as(integratorMagasin())
            .post("/api/platform/search/index/" + name + "/cancel")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));
    }
}
