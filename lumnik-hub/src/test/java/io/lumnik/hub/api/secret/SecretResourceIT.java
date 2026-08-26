package io.lumnik.hub.api.secret;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class SecretResourceIT {

    @Test
    void create_then_list_then_describe_then_delete() {
        String name = "IT_TEST_KEY_" + System.nanoTime();

        // CREATE
        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"super-secret\",\"description\":\"unit test\"}")
            .post("/api/platform/secrets")
            .then().statusCode(201);

        // LIST
        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .get("/api/platform/secrets")
            .then().statusCode(200)
            .body(containsString(name))
            .body(not(containsString("super-secret")));  // value NEVER exposed

        // DESCRIBE
        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name)
            .then().statusCode(200)
            .body(containsString(name))
            .body(containsString("unit test"))
            .body(not(containsString("super-secret")));

        // DELETE
        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .delete("/api/platform/secrets/" + name)
            .then().statusCode(204);

        // Verify gone
        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name)
            .then().statusCode(404);
    }

    /**
     * Tenant comes ONLY from the auth-resolved context — no tenant → 401, never a silent
     * fall-through to tenant 1's secrets.
     */
    @Test
    void list_without_tenant_in_context_returns_401() {
        RestAssured.given()
            .get("/api/platform/secrets")
            .then().statusCode(401);
    }

    @Test
    void update_records_rotated_at() {
        String name = "ROT_KEY_" + System.nanoTime();

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v1\"}")
            .post("/api/platform/secrets").then().statusCode(201);

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"value\":\"v2\"}")
            .put("/api/platform/secrets/" + name).then().statusCode(204);

        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name)
            .then().statusCode(200)
            .body(containsString("rotatedAt"));
    }
}
