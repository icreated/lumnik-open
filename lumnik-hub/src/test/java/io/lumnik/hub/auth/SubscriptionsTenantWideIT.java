package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * #1207: a subscription receives the whole tenant's events — another métier's row activity, the
 * documents its workflows find dormant, its connectors' runs — and mails or POSTs them where its
 * author chose. So subscriptions are tenant-wide: a caller bound to {@code scope:*} roles is refused
 * every door of both channels, notification rules and webhooks, with the uniform refusal.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class SubscriptionsTenantWideIT {

  private static final Set<String> ADMIN = Set.of("lm_admin", "lm_integrator");

  private static RequestSpecification as(Set<String> roles) {
    return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(roles, null))
        .header("Content-Type", "application/json");
  }

  private static RequestSpecification bound() {
    Set<String> roles = new HashSet<>(ADMIN);
    roles.add("scope:magasin");
    return as(roles);
  }

  private static void refused(Response r) {
    r.then().statusCode(403).body("error", equalTo("scope not granted"));
  }

  @Test
  void a_bound_admin_is_refused_every_notification_rule_door() {
    refused(bound().get("/api/notifications"));
    refused(bound().body("{\"eventPattern\":\"decision.*\",\"recipient\":\"a@b.c\"}").post("/api/notifications"));
    refused(bound().delete("/api/notifications/1"));
  }

  @Test
  void a_bound_admin_is_refused_every_webhook_door() {
    refused(bound().get("/api/tenants/1/integrations"));
    refused(bound().body("{\"name\":\"x\",\"url\":\"https://hooks.example/x\",\"eventTypes\":[\"decision.*\"]}")
        .post("/api/tenants/1/integrations"));
    refused(bound().get("/api/tenants/1/integrations/1/deliveries"));
    // the door lm integration calls: it delegates to the one above by injection, which a
    // request filter does not follow — so it carries the refusal itself
    refused(bound().get("/api/platform/integrations"));
    refused(bound().body("{\"name\":\"x\",\"url\":\"https://hooks.example/x\",\"eventTypes\":[\"decision.*\"]}")
        .post("/api/platform/integrations"));
    refused(bound().delete("/api/platform/integrations/1"));
    refused(bound().get("/api/platform/deliveries"));
    refused(bound().post("/api/platform/deliveries/1/replay"));
  }

  @Test
  void a_bound_admin_is_refused_the_live_event_stream() {
    // the same events a webhook receives, pulled instead of pushed
    refused(bound().accept("text/event-stream").get("/api/platform/events/stream"));
  }

  @Test
  void an_unbound_admin_keeps_both_channels() {
    as(ADMIN).get("/api/notifications").then().statusCode(200);
    as(ADMIN).get("/api/tenants/1/integrations").then().statusCode(200);
    as(ADMIN).get("/api/platform/integrations").then().statusCode(200);
    as(ADMIN).get("/api/platform/deliveries").then().statusCode(200);
  }
}
