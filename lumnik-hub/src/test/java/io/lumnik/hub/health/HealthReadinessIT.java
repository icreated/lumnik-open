package io.lumnik.hub.health;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the SmallRye health endpoints used as k8s readiness/liveness probes.
 * The readiness check asserts the datasource health check is present and UP, so
 * the hub is only marked Ready once its database is reachable.
 *
 * <p>#1993: they answer on the management port only, which nothing routes; the application port carries the
 * application and nothing of {@code /q} but what dev and test add (the spec, Swagger UI).
 */
@QuarkusTest
class HealthReadinessIT {

    @TestHTTPResource(value = "/health/ready", management = true)   // under the management root, /q
    URL ready;

    @TestHTTPResource(value = "/health/live", management = true)
    URL live;

    @TestHTTPResource(value = "/metrics", management = true)
    URL metrics;

    @Test
    void readinessEndpointIsUpAndDatasourceChecked() {
        given()
            .when().get(ready)
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
            .when().get(live)
            .then()
            .statusCode(200)
            .body("status", is("UP"));
    }

    @Test
    void metricsAnswerOnTheManagementPort() {
        given().when().get(metrics).then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/q/health", "/q/health/ready", "/q/health/live", "/q/metrics"})
    void theApplicationPortServesNoneOfThem(String path) {
        given().when().get(path).then().statusCode(404);
    }
}
