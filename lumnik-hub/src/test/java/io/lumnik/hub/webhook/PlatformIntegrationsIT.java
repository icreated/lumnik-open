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

    @Test @Order(3)
    void create_with_an_unknown_key_is_refused_by_name() {
        // `url` for `targetUrl`: the door must name the key, not store a target without a URL.
        asAdmin()
            .body("{\"name\":\"n8n-misspelt\",\"url\":\"http://localhost:9/webhook\","
                + "\"eventTypes\":[\"decision.*\"],\"secret\":\"s3cret\"}")
            .post("/api/platform/integrations")
        .then().statusCode(400)
            .contentType("application/json")
            .body("error", containsString("unknown key: url —"))
            .body("error", containsString("targetUrl"))
            .body("error", not(containsString("s3cret")))
            .body("error", not(containsString("localhost:9")));
        asAdmin().get("/api/platform/integrations")
        .then().body("find { it.name == 'n8n-misspelt' }", nullValue());
    }

    @Test @Order(3)
    void update_with_an_unknown_key_is_refused_by_name() {
        // An integration has no entity filter on this door: dropping the key would answer 200
        // and leave the target receiving every table's events.
        asAdmin()
            .body("{\"enabled\":false,\"entityFilter\":\"connector.t_orders\"}")
            .put("/api/platform/integrations/" + createdId)
        .then().statusCode(400)
            .body("error", containsString("unknown key: entityFilter —"))
            .body("error", containsString("eventTypes"));
        asAdmin().get("/api/platform/integrations/" + createdId)
        .then().statusCode(200).body("enabled", is(true));
    }

    @Test @Order(4)
    void delete_via_platform_path_is_204() {
        asAdmin().delete("/api/platform/integrations/" + createdId)
        .then().statusCode(204);
        asAdmin().get("/api/platform/integrations")
        .then().body("find { it.name == 'n8n-it' }", nullValue());
    }
}
