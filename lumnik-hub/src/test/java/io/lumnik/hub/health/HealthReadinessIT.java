package io.lumnik.hub.health;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the SmallRye health endpoints used as k8s readiness/liveness probes.
 * The readiness check asserts the datasource health check is present and UP, so
 * the hub is only marked Ready once its database is reachable.
 */
@QuarkusTest
class HealthReadinessIT {

    @Test
    void readinessEndpointIsUpAndDatasourceChecked() {
        given()
            .when().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", is("UP"))
            // Substring match on the datasource check name: the exact SmallRye
            // label varies across Quarkus versions / multi-datasource setups,
            // so match on the stable "Database" token rather than the full name.
            .body("checks.find { it.name.contains('Database') }.status", is("UP"));
    }

    @Test
    void livenessEndpointIsUp() {
        given()
            .when().get("/q/health/live")
            .then()
            .statusCode(200)
            .body("status", is("UP"));
    }
}
