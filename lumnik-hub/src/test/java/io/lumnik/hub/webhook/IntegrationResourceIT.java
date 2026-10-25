package io.lumnik.hub.webhook;

import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class IntegrationResourceIT {

    static final Long TENANT = 999921L;

    @BeforeEach
    void setTenant() {
        TenantContextFilter.CURRENT_TENANT.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContextFilter.CURRENT_TENANT.remove();
    }

    @Test
    void create_list_update_delete_roundtrip() {
        Integer id = given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"make-repairs","targetUrl":"https://hook.make.com/abc",
                   "platform":"make","authMode":"BEARER",
                   "secret":"sk-test-123",
                   "eventTypes":["repair.completed"]}""")
        .when().post("/api/tenants/" + TENANT + "/integrations")
        .then().statusCode(201)
            .body("name", equalTo("make-repairs"))
            .body("platform", equalTo("make"))
            .body("authMode", equalTo("BEARER"))
            .body("hasSecret", equalTo(true))
            .extract().path("id");

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(200)
            .body("name", hasItem("make-repairs"));

        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"enabled": false}""")
        .when().put("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(200)
            .body("enabled", equalTo(false));

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().delete("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(204);
    }

    /**
     * Deleting an integration is a SOFT delete (IntegrationResource.delete → softDelete()), and
     * every reader is live-only (listLiveForTenant, findLiveForTenant, the delivery fan-out). The
     * name it held must therefore be free again — uq_webhook_endpoint_name counted the tombstone,
     * so re-creating "the slack hook" after removing it died at commit with 23505 and answered an
     * anonymous 500. The name was gone from the list and unusable at the same time.
     */
    @Test
    void a_name_freed_by_delete_can_be_used_again() {
        String name = "readd-hook-" + System.nanoTime();

        Integer id = createIntegration(name).then().statusCode(201).extract().path("id");

        given().header("X-Tenant-ID", TENANT.toString())
        .when().delete("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(204);

        createIntegration(name).then().statusCode(201);

        given().header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(200)
            .body("findAll { it.name == '" + name + "' }.size()", equalTo(1));
    }

    /**
     * The create door wrote the row with no name check at all, so a second LIVE integration under
     * a name already taken was not refused — it reached uq_webhook_endpoint_name and came back as
     * an anonymous 500. The rule the constraint enforces is the door's to state: one live name,
     * said as a 409 that names it.
     */
    @Test
    void a_second_integration_under_a_live_name_is_refused_by_name() {
        String name = "duplicate-hook-" + System.nanoTime();

        Integer holder = createIntegration(name).then().statusCode(201).extract().path("id");

        // The name came from the caller, so echoing it alone says only "taken". The id is the
        // handle every other door takes (DELETE /{id}, PUT /{id}), so the refusal ends the
        // caller's question instead of sending them to `list` to ask it again.
        createIntegration(name).then()
            .statusCode(409)
            .body("error", containsString(name))
            .body("error", containsString("(id " + holder + ")"));
    }

    /**
     * The rename half of the same rule, one method down: moving an integration onto a name another
     * live one holds is the same collision, and answered the same anonymous 500. The second PUT is
     * what keeps the fix honest — a row must still be allowed to keep its OWN name.
     */
    @Test
    void renaming_an_integration_onto_a_live_name_is_refused_but_it_keeps_its_own() {
        String taken = "taken-hook-" + System.nanoTime();
        String mine = "moving-hook-" + System.nanoTime();

        Integer holder = createIntegration(taken).then().statusCode(201).extract().path("id");
        Integer id = createIntegration(mine).then().statusCode(201).extract().path("id");

        // Naming the HOLDER, not the row being renamed: a swapped holder/self would print this
        // row's own id and send the caller back to itself. Anchored on the rendered "(id N)" —
        // a bare containsString would match digits inside the nanoTime in the name.
        given().contentType("application/json").header("X-Tenant-ID", TENANT.toString())
            .body("{\"name\":\"" + taken + "\"}")
        .when().put("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(409)
            .body("error", containsString("(id " + holder + ")"));

        given().contentType("application/json").header("X-Tenant-ID", TENANT.toString())
            .body("{\"name\":\"" + mine + "\",\"enabled\":false}")
        .when().put("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(200)
            .body("name", equalTo(mine))
            .body("enabled", equalTo(false));
    }

    @Test
    void an_unknown_key_is_refused_by_name_on_create_and_update() {
        String name = "strict-hook-" + System.nanoTime();
        given().contentType("application/json").header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"%s","targetUrl":"https://hook.make.com/abc","authMode":"NONE",
                   "eventTypes":["repair.completed"],"event_types":["x"]}""".formatted(name))
        .when().post("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(400)
            .body("error", containsString("unknown key: event_types —"));

        Integer id = createIntegration(name).then().statusCode(201).extract().path("id");

        given().contentType("application/json").header("X-Tenant-ID", TENANT.toString())
            .body("{\"enable\":false}")
        .when().put("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(400)
            .body("error", containsString("unknown key: enable —"));
    }

    private Response createIntegration(String name) {
        return given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"%s","targetUrl":"https://hook.make.com/abc",
                   "platform":"make","authMode":"BEARER","secret":"sk-test-123",
                   "eventTypes":["repair.completed"]}""".formatted(name))
        .when().post("/api/tenants/" + TENANT + "/integrations");
    }

    @Test
    void deliveries_with_zero_limit_answers_instead_of_throwing() {
        Integer id = given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"zero-limit-probe","targetUrl":"https://hook.make.com/zero",
                   "platform":"make","authMode":"BEARER",
                   "secret":"sk-test-123",
                   "eventTypes":["repair.completed"]}""")
        .when().post("/api/tenants/" + TENANT + "/integrations")
        .then().statusCode(201)
            .extract().path("id");

        // limit=0 is a nonsense request; it must clamp, not surface page(0,0)'s exception as a 500.
        given().header("X-Tenant-ID", TENANT.toString())
            .when().get("/api/tenants/" + TENANT + "/integrations/" + id + "/deliveries?limit=0")
            .then().statusCode(200);
        given().header("X-Tenant-ID", TENANT.toString())
            .when().get("/api/tenants/" + TENANT + "/integrations/" + id + "/deliveries?limit=0&status=failed")
            .then().statusCode(200);

        given().header("X-Tenant-ID", TENANT.toString())
            .when().delete("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(204);
    }

    @Test
    void list_without_tenant_context_is_forbidden() {
        // No X-Tenant-ID header: the request-thread TenantContextFilter never sets a tenant,
        // so requireTenant() must fail closed rather than silently let an arbitrary tid through.
        given()
        .when().get("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(403);
    }

    @Test
    void test_event_with_unknown_type_returns_400() {
        Integer id = given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"test-1","targetUrl":"https://example.com/hook",
                   "platform":"generic","authMode":"NONE",
                   "eventTypes":["org.created"]}""")
        .when().post("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(201).extract().path("id");

        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"eventType":"does.not.exist"}""")
        .when().post("/api/tenants/" + TENANT + "/integrations/" + id + "/test")
            .then().statusCode(400)
            // The BODY, not only the status: this door refuses by throwing, and until
            // BadRequestExceptionMapper that refusal crossed the wire empty — admin-console-guide.md
            // documented the emptiness three times and sent the reader to the SERVER LOG for the
            // sentence. The doc now quotes this body, so the claim is pinned where the door is.
            .body("error", equalTo("Unknown event type: does.not.exist"));
    }

    /** #1239 — why in IntegrationResource.requireSecretForSigning. */
    @ParameterizedTest
    @ValueSource(strings = {"", "\"authMode\":\"HMAC\",", "\"authMode\":\"BEARER\","})
    void a_signing_mode_without_a_secret_is_a_400_and_persists_nothing(String mode) {
        String name = "nosecret-" + System.nanoTime();
        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{" + mode + "\"name\":\"" + name + "\",\"targetUrl\":\"https://hook.example.com/x\",\"eventTypes\":[\"repair.completed\"]}")
        .when().post("/api/tenants/" + TENANT + "/integrations")
        .then().statusCode(400)
            .body("error", containsString("secret"));

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(200)
            .body("name", not(hasItem(name)));
    }

    @Test
    void switching_an_unsigned_integration_to_hmac_without_a_secret_is_a_400() {
        // The setup is also the proof that authMode NONE needs no secret.
        Integer id = given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"authMode\":\"NONE\",\"name\":\"toggle-" + System.nanoTime()
                + "\",\"targetUrl\":\"https://hook.example.com/x\",\"eventTypes\":[\"repair.completed\"]}")
        .when().post("/api/tenants/" + TENANT + "/integrations")
        .then().statusCode(201).extract().path("id");

        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"authMode\":\"HMAC\"}")
        .when().put("/api/tenants/" + TENANT + "/integrations/" + id)
        .then().statusCode(400);

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(200)
            .body("authMode", equalTo("NONE"));
    }
}
