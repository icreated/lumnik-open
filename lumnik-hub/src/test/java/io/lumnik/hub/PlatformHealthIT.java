package io.lumnik.hub;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Lightweight smoke test — verifies that Quarkus starts (Flyway runs) and the
 * health endpoint responds. Used to validate Flyway migrations without
 * exercising any specific business logic.
 */
@QuarkusTest
class PlatformHealthIT {

    @Test
    void health_endpoint_responds() {
        given()
            .when().get("/api/platform/health")
            .then().statusCode(200)
                   .body("platform", equalTo("ok"));
    }
}
