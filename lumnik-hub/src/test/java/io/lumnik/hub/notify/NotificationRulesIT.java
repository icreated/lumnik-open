package io.lumnik.hub.notify;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The email-rule surface (chantier C3): notification_rule existed since V24 with no write
 * path — EmailConsumer swept an eternally-empty table. Tenant-implicit path (the
 * InboxResource pattern), lm_admin; the resource filters by tenant explicitly.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRulesIT {

    private static final String TENANT = "7001";
    private static Long createdId;

    private static RequestSpecification asAdmin(String tenant) {
        return given().header("X-Tenant-ID", tenant).header("X-User-ID", "1")
                      .header("Content-Type", "application/json");
    }

    @Test @Order(1)
    void create_rule_is_201() {
        createdId = ((Number) asAdmin(TENANT)
            .body("{\"eventPattern\":\"decision.*\",\"recipient\":\"adv@client.example\"}")
            .post("/api/notifications")
        .then()
            .statusCode(201)
            .body("eventPattern", equalTo("decision.*"))
            .body("recipient", equalTo("adv@client.example"))
            .body("template", equalTo("event-notification"))
            .body("enabled", is(true))
            .extract().path("id")).longValue();
    }

    @Test @Order(2)
    void unsafe_template_name_is_refused() {
        asAdmin(TENANT)
            .body("{\"eventPattern\":\"decision.*\",\"recipient\":\"a@b.c\",\"template\":\"../../etc/passwd\"}")
            .post("/api/notifications")
        .then().statusCode(400)
            .body("error", containsString("template"));
    }

    @Test @Order(3)
    void list_is_tenant_bound() {
        asAdmin(TENANT).get("/api/notifications")
        .then().statusCode(200)
            .body("find { it.recipient == 'adv@client.example' }.eventPattern", equalTo("decision.*"));
        asAdmin("7002").get("/api/notifications")
        .then().statusCode(200)
            .body("find { it.recipient == 'adv@client.example' }", nullValue());
    }

    @Test @Order(4)
    void delete_is_204_and_removes() {
        asAdmin(TENANT).delete("/api/notifications/" + createdId).then().statusCode(204);
        asAdmin(TENANT).get("/api/notifications")
        .then().body("find { it.recipient == 'adv@client.example' }", nullValue());
    }
}
