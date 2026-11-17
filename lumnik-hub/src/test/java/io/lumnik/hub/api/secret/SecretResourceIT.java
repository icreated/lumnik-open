package io.lumnik.hub.api.secret;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class SecretResourceIT {

    @Inject @DataSource("admin") AgroalDataSource admin;

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

    /**
     * `secret rm` was a SOFT delete: deleted_at was stamped and the row stayed. Every reader filtered
     * it out — SecretRepository.findByName/listByTenant, PgcryptoSecretResolver, and both partial
     * indexes V14 declares — so by every definition the application uses, the name was free again.
     * UNIQUE (tenant_id, name) did not agree: it still counted the tombstone, the create door's
     * INSERT raised 23505, and the caller got an anonymous 500 for a name `list` no longer showed
     * and `rm` answered 404 for. No command could free it. Since V84 the delete removes the row
     * itself, and the name must stay reusable.
     */
    @Test
    void a_name_freed_by_delete_can_be_created_again() {
        String name = "REUSE_KEY_" + System.nanoTime();

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v1\",\"description\":\"first\"}")
            .post("/api/platform/secrets").then().statusCode(201);

        RestAssured.given().header("X-Tenant-Id", "1")
            .delete("/api/platform/secrets/" + name).then().statusCode(204);

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v2\",\"description\":\"second\"}")
            .post("/api/platform/secrets").then().statusCode(201);

        // The live row is the SECOND one — describe reads through the same deleted_at IS NULL
        // filter the unique index now uses, so a name can hold exactly one live secret at a time.
        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name)
            .then().statusCode(200).body(containsString("second"));
        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets")
            .then().statusCode(200)
            .body("findAll { it.name == '" + name + "' }.size()", is(1));

        // And the live name is still taken: freeing a deleted name must not free a live one.
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v3\"}")
            .post("/api/platform/secrets").then().statusCode(409);
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

    /** lm rotates without --description by leaving the field out; the label must survive (#1249). */
    @Test
    void an_update_without_description_keeps_it() {
        String name = "DESC_KEY_" + System.nanoTime();

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v1\",\"description\":\"first desc\"}")
            .post("/api/platform/secrets").then().statusCode(201);

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"value\":\"v2\"}")
            .put("/api/platform/secrets/" + name).then().statusCode(204);

        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name)
            .then().statusCode(200)
            .body("description", equalTo("first desc"));
    }

    /**
     * #2015: the scopes a secret serves are set apart from its value, so giving a secret its scopes on upgrade never means
     * typing the value again. The list is replaced, and an empty one makes it scopeless again.
     */
    @Test
    void the_scopes_door_replaces_the_scopes_and_keeps_the_value() {
        String name = "SCOPES_KEY_" + System.nanoTime();
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v1\"}")
            .post("/api/platform/secrets").then().statusCode(201);
        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name).then().statusCode(200)
            .body("scopes", empty());

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("[\"ventes\",\"achats\",\"ventes\"]")
            .put("/api/platform/secrets/" + name + "/scopes").then().statusCode(200)
            .body("", contains("ventes", "achats"));
        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name).then().statusCode(200)
            .body("scopes", contains("ventes", "achats"))
            .body("rotatedAt", nullValue());

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("[]")
            .put("/api/platform/secrets/" + name + "/scopes").then().statusCode(200);
        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name).then().statusCode(200)
            .body("scopes", empty());

        // Widening and narrowing are both lifecycle changes: the event stream and the audit trail say from what to what.
        assertEquals(List.of("|ventes,achats", "ventes,achats|"), Jdbc.query(admin, "it",
            "SELECT payload->>'previousScopes' || '|' || (payload->>'scopes') FROM core.event_outbox"
                + " WHERE event_type = 'platform.secret.scoped' AND payload->>'name' = ? ORDER BY id",
            ps -> ps.setString(1, name), rs -> rs.getString(1)));
        assertEquals(List.of("[]|[\"ventes\", \"achats\"]", "[\"ventes\", \"achats\"]|[]"), Jdbc.query(admin, "it",
            "SELECT (changes->'previousScopes')::text || '|' || (changes->'scopes')::text FROM core.audit_outbox"
                + " WHERE entity_type = 'Secret' AND changes->>'name' = ? AND changes->'scopes' IS NOT NULL ORDER BY id",
            ps -> ps.setString(1, name), rs -> rs.getString(1)));
    }

    /**
     * The connector doors' rule for a scope name, a 404 for a name nothing holds, and no list at all refused: an empty
     * body must not strip a secret of its scopes, {@code []} says so.
     */
    @Test
    void the_scopes_door_refuses_a_malformed_scope_and_an_unknown_secret() {
        String name = "SCOPES_BAD_" + System.nanoTime();
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v1\"}")
            .post("/api/platform/secrets").then().statusCode(201);

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("[\"Ventes,achats\"]")
            .put("/api/platform/secrets/" + name + "/scopes").then().statusCode(400)
            .body("error", containsString("scope must be lowercase"));
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("[\"ventes\"]")
            .put("/api/platform/secrets/NO_SUCH_" + name + "/scopes").then().statusCode(404);
        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("[\"ventes\"]")
            .put("/api/platform/secrets/" + name + "/scopes").then().statusCode(200);
        for (String none : List.of("null", "")) {
            RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
                .body(none)
                .put("/api/platform/secrets/" + name + "/scopes").then().statusCode(400)
                .body("error", containsString("[] for none"));
        }
        RestAssured.given().header("X-Tenant-Id", "1")
            .get("/api/platform/secrets/" + name).then().statusCode(200)
            .body("scopes", contains("ventes"));
    }
}
