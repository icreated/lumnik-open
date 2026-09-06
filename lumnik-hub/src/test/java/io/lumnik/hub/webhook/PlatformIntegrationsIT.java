package io.lumnik.hub.webhook;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The lm-callable integrations family (chantier C2/P17): tenant from the request context,
 * never a URL parameter — lm carries no numeric tenant against a real hub (the same reason
 * PlatformDeliveryResource exists). Delegates to the tenant-in-URL family's logic.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformIntegrationsIT {

    private static final String TENANT = "6001";
    private static Long createdId;

    private static RequestSpecification asAdmin() {
        return given().header("X-Tenant-ID", TENANT).header("X-User-ID", "1")
                      .header("Content-Type", "application/json");
    }

    @Test @Order(1)
    void create_via_platform_path_is_201() {
        createdId = ((Number) asAdmin()
            .body("{\"name\":\"n8n-it\",\"targetUrl\":\"http://localhost:9/webhook\","
                + "\"eventTypes\":[\"decision.*\"],\"secret\":\"s3cret\"}")
            .post("/api/platform/integrations")
        .then()
            .statusCode(201)
            .body("name", equalTo("n8n-it"))
            .extract().path("id")).longValue();
    }

    @Test @Order(2)
    void list_shows_it_and_never_the_secret() {
        asAdmin().get("/api/platform/integrations")
        .then().statusCode(200)
            .body("find { it.name == 'n8n-it' }.eventTypes", hasItem("decision.*"))
            .body("find { it.name == 'n8n-it' }.secret", nullValue());
    }

    @Test @Order(3)
    void another_tenant_does_not_see_it() {
        given().headers(TestIdentity.tenantAndUser(6002))
            .get("/api/platform/integrations")
        .then().statusCode(200)
            .body("find { it.name == 'n8n-it' }", nullValue());
    }

    @Test @Order(4)
    void delete_via_platform_path_is_204() {
        asAdmin().delete("/api/platform/integrations/" + createdId)
        .then().statusCode(204);
        asAdmin().get("/api/platform/integrations")
        .then().body("find { it.name == 'n8n-it' }", nullValue());
    }
}
