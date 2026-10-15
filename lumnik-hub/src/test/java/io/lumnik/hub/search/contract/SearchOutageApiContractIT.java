package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(SearchOutageApiContractProfile.class)
class SearchOutageApiContractIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /**
     * {@code SearchGate.search()} answers a bare empty success — never the provider — when the
     * caller's {@code allowedEntities} set is empty (its own short-circuit, checked AFTER provider
     * selection so an unauthorized caller still learns Search is absent rather than getting a
     * quiet empty result). A fresh test database starts with zero {@code kind: Entity} manifests,
     * so the outage double is never actually asked without this: one real, unscoped-visible entity,
     * exactly as {@code SearchIndexItSupport}'s sibling fixtures do for every other IT in this
     * package that exercises a real query through {@code SearchGate}.
     */
    private void applyOneVisibleEntity() throws Exception {
        String name = "outagecontract" + System.nanoTime();
        String table = "t_" + name;
        EntitySourceTableFixture.create(adminDs, table, "v_" + name.toLowerCase(Locale.ROOT), "src_code");
        String manifest = "kind: Entity\n"
            + "name: " + name + "\n"
            + "match: [code]\n"
            + "scopes: []\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + "      src_code: code\n";
        RestAssured.given().config(YamlBody.CONFIG)
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_admin"), null))
            .contentType("application/yaml").body(manifest)
            .post("/api/entities/apply").then().statusCode(200);
    }

    @Test
    void apiAnswersTheExplicitDegradedShapeNeverAnEmptySuccess() throws Exception {
        applyOneVisibleEntity();

        RestAssured.given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_user"), null))
            .queryParam("q", "anything")
            .get("/api/search")
            .then().statusCode(503)
            .body("reason", notNullValue());
    }

    @Test
    void hubReadinessStaysGreenThroughoutTheOutage() {
        RestAssured.given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_integrator"), null))
            .get("/api/platform/health")
            .then().statusCode(200)
            .body("platform", equalTo("ok"));
    }

    @Test
    void theConfigPinActuallySelectedTheDoubleNotAmbiguousProvider() {
        RestAssured.given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_user"), null))
            .get("/api/search/capability")
            .then().statusCode(200)
            .body("state", equalTo("degraded"))
            .body("reason", not(equalTo("ambiguous_provider")))
            .body("provider", equalTo(OutageDoubleSearchProvider.NAME));
    }
}
