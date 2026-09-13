package io.lumnik.hub.event;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class EventRegistryIT {

    @Test
    void platform_event_catalog_lists_known_events() {
        given()
        .when().get("/api/platform/events")
        .then().statusCode(200)
            .body("type", hasItem("org.created"))
            .body("type", hasItem("connector.run.started"));
    }

    /** Every statically-emitted type must be in the catalog — an integrator discovering the
     *  spine through GET /api/platform/events must not miss most of it (register P15).
     *  decision.* families are dynamic (named per rule/workflow) and stay out by doctrine. */
    @Test
    void catalog_covers_every_statically_emitted_type() {
        String[] emitted = {
            "org.created", "org.updated", "org.moved", "org.archived",
            "connector.run.started", "connector.run.completed",
            "connector.run.partial", "connector.run.cancelled", "connector.run.failed",
            "platform.secret.created", "platform.secret.updated",
            "platform.secret.deleted", "platform.secret.read",
            "data.row.changed", "annotation.decision.acked",
        };
        var body = given().when().get("/api/platform/events").then().statusCode(200);
        for (String type : emitted) {
            body.body("type", hasItem(type));
        }
    }
}
