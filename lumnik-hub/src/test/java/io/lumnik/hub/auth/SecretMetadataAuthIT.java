package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;

/**
 * P11: pins that secret METADATA (names, descriptions — the map of your infrastructure)
 * is integrator surface, not any-authenticated-user surface. The register's premise
 * ("readable by any authenticated tenant user") was WRONG — the platform filter already
 * gated it; this test proves the gate under the no-bypass posture and the explicit
 * annotations keep it stated in code.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class SecretMetadataAuthIT {

    @Test
    void lm_user_cannot_list_secret_metadata() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_user"), null))
            .when().get("/api/platform/secrets")
            .then().statusCode(403);
    }

    @Test
    void integrator_can_list() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_integrator"))
            .when().get("/api/platform/secrets")
            .then().statusCode(200);
    }
}
