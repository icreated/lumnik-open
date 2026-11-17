package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.lumnik.hub.auth.TenantWideCalls.ADMIN;
import static io.lumnik.hub.auth.TenantWideCalls.as;
import static io.lumnik.hub.auth.TenantWideCalls.bound;
import static io.lumnik.hub.auth.TenantWideCalls.refused;
import static org.hamcrest.Matchers.containsString;

/**
 * #1992, #2011: the hook doors act for the whole tenant — an override switches a hook for every scope, the registry
 * names every binding — so a caller bound to a scope is refused each of them, as the subscription doors refuse it.
 * And an override carries no code: the hub runs the operator's scripts only.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class HookDoorsTenantWideIT {

  @Test
  void a_bound_admin_is_refused_every_hook_door() {
    refused(bound().get("/api/platform/hooks"));
    refused(bound().get("/api/platform/hooks/any"));
    refused(bound().body("{\"enabled\":false}").post("/api/platform/hooks/any/toggle"));
    refused(bound().get("/api/platform/hooks/overrides?tenantId=1"));
    refused(bound().body("{\"hookId\":\"any\",\"tenantId\":1}").post("/api/platform/hooks/overrides"));
    refused(bound().delete("/api/platform/hooks/overrides/any?tenantId=1"));
    refused(bound().get("/api/platform/admin/hooks"));
  }

  @Test
  void an_unbound_admin_keeps_the_hook_reads() {
    as(ADMIN).get("/api/platform/hooks").then().statusCode(200);
    as(ADMIN).get("/api/platform/admin/hooks").then().statusCode(200);
  }

  @Test
  void an_override_carries_no_code() {
    as(ADMIN).body("{\"hookId\":\"any\",\"tenantId\":1,\"script\":\"java.lang.System.getenv()\"}")
        .post("/api/platform/hooks/overrides")
        .then().statusCode(400)
        .body("error", containsString("carries no code"))
        .body("error", containsString("/api/platform/hooks/{hookId}/toggle"));
  }
}
