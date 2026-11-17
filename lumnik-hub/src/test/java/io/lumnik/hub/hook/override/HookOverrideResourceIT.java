package io.lumnik.hub.hook.override;

import io.lumnik.hub.hook.HookOverrideStore;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HookOverrideResourceIT {

    static final Long TENANT = 999960L;
    static final String HOOK_ID = "io.lumnik.test.Override";

    @Inject HookOverrideStore store;

    @BeforeEach void setTenant() { TenantContextFilter.CURRENT_TENANT.set(TENANT); }

    @AfterEach void clear() {
        try {
            given().header("X-Tenant-ID", TENANT.toString())
                   .delete("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT);
        } finally {
            TenantContextFilter.CURRENT_TENANT.remove();
        }
    }

    /** #2011: an override carries no code — a script is refused, and nothing is stored. */
    @Test
    void an_override_with_a_script_is_refused_and_stores_nothing() {
        given()
            .contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"hookId\": \"" + HOOK_ID + "\", \"tenantId\": " + TENANT + ", \"script\": \"val x = 42\"}")
        .when().post("/api/platform/hooks/overrides")
        .then().statusCode(400)
               .body("error", containsString("carries no code"));

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT)
        .then().statusCode(404);
    }

    /** What an override holds is the toggle: read back without any script field. */
    @Test
    void a_toggle_is_read_back_as_the_override_without_a_script() {
        toggle(false);

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT)
        .then().statusCode(200)
               .body("hookId", equalTo(HOOK_ID))
               .body("enabled", equalTo(false))
               .body("$", not(hasKey("script")));
    }

    @Test
    void delete_removes_override() {
        toggle(false);

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().delete("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT)
        .then().statusCode(204);

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT)
        .then().statusCode(404);
    }

    /** What the toggle door stores, written through the same store: the test hook id is not in the registry. */
    private void toggle(boolean enabled) {
        store.set(TENANT, HOOK_ID, enabled);
    }

    @Test
    void list_requires_tenantId() {
        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks/overrides")
        .then().statusCode(400);
    }
}
