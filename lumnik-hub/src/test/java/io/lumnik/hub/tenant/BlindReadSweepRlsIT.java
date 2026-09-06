package io.lumnik.hub.tenant;

import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThan;

/**
 * The hub's doors of the #385 defect, walked under the posture they run in.
 *
 * <p>The mechanism and the guardian for the door written next are in
 * {@link BlindReadInventoryTest}. What this file adds is proof on real HTTP: every case below was
 * seen RED before the fix, and the failures came in two shapes because the policies do.
 * {@code core.*} carries V21's {@code NULLIF(…, '')} guard, so those doors answered
 * {@code Actual: []} — the production symptom, a store that reads as empty. {@code identity.*}
 * had no such guard, so those answered <b>HTTP 500</b>, {@code invalid input syntax for type
 * bigint: ""}; identity's V18 closes that half.
 *
 * <p>Seven of the hub's nine sites are walked here. {@code IntegrationResource}'s
 * {@code /{id}/deliveries} is not: a delivery row is written by the webhook consumer, not through
 * any endpoint, so there is no honest way to make one from a REST walk. It carries the same
 * annotation and is covered by the inventory test alone — stated rather than implied.
 * {@code ScimTokenResource.list} is walked by {@code ScimTokenBlindReadRlsClosedIT}, under the
 * closed {@code pro/scim} tree: SCIM is closed, and a case that hits its route from this file
 * would make the open edition's release proof fail on a 404. The nine identity-module sites the widened inventory found are
 * covered there too; this file stays on the hub's own open surface.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class BlindReadSweepRlsIT {

  /** {@code IntegrationResource.list} and {@code .one} — {@code core.webhook_endpoint}. */
  @Test
  void a_webhook_endpoint_that_was_written_can_be_listed_and_fetched() {
    String name = "RLS_IT_" + System.nanoTime();
    int id = RestAssured.given().headers(TestIdentity.tenantOnly(1)).contentType("application/json")
        .body("{\"name\":\"" + name + "\",\"targetUrl\":\"http://localhost:9/hook\","
            + "\"eventTypes\":[\"org.created\"]}")
        .post("/api/tenants/1/integrations")
        .then().statusCode(201).extract().path("id");

    RestAssured.given().headers(TestIdentity.tenantOnly(1))
        .get("/api/tenants/1/integrations")
        .then().statusCode(200).body(containsString(name));

    RestAssured.given().headers(TestIdentity.tenantOnly(1))
        .get("/api/tenants/1/integrations/" + id)
        .then().statusCode(200).body("name", is(name));
  }

  /**
   * {@code IntegrationResource.test} — the {@code @POST} the {@code @GET} sweep did not look at.
   *
   * <p>{@code lm integration test ID} answered 404 on every real hub: the door reads the endpoint
   * it is about to fire at, outside a transaction, so the row it had just been shown by
   * {@code list} was not there. The target refuses the connection on purpose (port 9, as the
   * other webhook ITs do) — the claim is that the door FINDS the endpoint (200, with the
   * delivery's own verdict inside), not that anything arrives.
   */
  @Test
  void a_registered_webhook_can_be_test_fired() {
    int id = RestAssured.given().headers(TestIdentity.tenantOnly(1)).contentType("application/json")
        .body("{\"name\":\"RLS_IT_test_" + System.nanoTime() + "\","
            + "\"targetUrl\":\"http://localhost:9/hook\",\"eventTypes\":[\"org.created\"]}")
        .post("/api/tenants/1/integrations")
        .then().statusCode(201).extract().path("id");

    RestAssured.given().headers(TestIdentity.tenantOnly(1)).contentType("application/json")
        .body("{\"eventType\":\"org.created\"}")
        .post("/api/tenants/1/integrations/" + id + "/test")
        .then().statusCode(200).body("ok", is(false));
  }

  /**
   * {@code OrgResource.create} and {@code .update} — {@code identity.org}, read BEFORE the
   * transaction on purpose, and blind for it.
   *
   * <p>Both doors keep the geocoder's HTTP round-trip outside the transaction, so the transaction
   * lives in {@code OrgTxOps} — and the checks that run first ({@code codeExists}, the parent
   * lookup, the cycle walk) ran with no transaction at all. Under enforced RLS every parent was
   * a 404, every update was a 404, and a duplicate code reached the unique constraint as a 500
   * instead of the 409 the check exists to give.
   */
  @Test
  void an_org_can_be_given_a_parent_and_updated() {
    String code = "RLS" + (System.nanoTime() % 1_000_000_000L);
    int root = RestAssured.given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json")
        .body("{\"code\":\"" + code + "\",\"name\":\"RLS root\"}")
        .post("/api/tenants/1/orgs")
        .then().statusCode(201).extract().path("id");

    RestAssured.given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json")
        .body("{\"code\":\"" + code + "C\",\"name\":\"RLS child\",\"parentOrgId\":" + root + "}")
        .post("/api/tenants/1/orgs")
        .then().statusCode(201);

    RestAssured.given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json")
        .body("{\"name\":\"RLS root, renamed\"}")
        .put("/api/tenants/1/orgs/" + root)
        .then().statusCode(200).body("name", is("RLS root, renamed"));

    RestAssured.given().headers(TestIdentity.tenantAndUser(1)).contentType("application/json")
        .body("{\"code\":\"" + code + "\",\"name\":\"RLS duplicate\"}")
        .post("/api/tenants/1/orgs")
        .then().statusCode(409);
  }

  /** {@code NotificationRuleResource.list} — {@code core.notification_rule}. */
  @Test
  void a_notification_rule_that_was_written_can_be_listed() {
    String recipient = "rls" + System.nanoTime() + "@example.invalid";
    RestAssured.given().headers(TestIdentity.tenantOnly(1)).contentType("application/json")
        .body("{\"eventPattern\":\"connector.run.*\",\"recipient\":\"" + recipient + "\"}")
        .post("/api/notifications").then().statusCode(201);

    RestAssured.given().headers(TestIdentity.tenantOnly(1))
        .get("/api/notifications").then().statusCode(200).body(containsString(recipient));
  }

  /**
   * {@code BffResource.me} — {@code identity.app_user}, and the one an operator meets first.
   * Blind, this endpoint did not answer "no user": it answered <b>500</b>, because identity's
   * policy casts the empty GUC directly. Either way the PWA's own greeting stops knowing who is
   * logged in; user 1 is the integrator seeded by identity V16.
   */
  @Test
  void the_bff_knows_which_user_is_asking() {
    RestAssured.given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/bff/v1/me")
        .then().statusCode(200).body("login", is("integrator"));
  }

  /**
   * {@code TenantListResource.list} — the one an annotation could not fix.
   *
   * <p>Its outer query reads {@code identity.tenant}, which has no policy, so the tenant NAMES
   * always came back; the counts read {@code identity.org} and {@code identity.app_user}, which
   * do. Under the caller's own GUC the caller's tenant counts correctly and every other tenant
   * reports <b>0</b> — pinned here as tenant 11, which identity V17 seeds with hubadmin. That is
   * why the endpoint moved to the {@code admin} datasource instead: no GUC value makes this query
   * right on the enforced one, since no identity policy honours the {@code '0'} sentinel either.
   */
  @Test
  void the_tenant_list_counts_users_in_tenants_other_than_the_callers() {
    RestAssured.given().headers(TestIdentity.tenantOnly(1))
        .get("/api/platform/admin/tenants")
        .then().statusCode(200)
        // greaterThan(0), not an exact count: other ITs create users in tenant 1 and the
        // reactor run shares one database. The defect made these ZERO, so zero is the whole
        // claim — tenant 11 is the load-bearing half, since it is never the caller's own.
        .body("find { it.id == 1 }.userCount", greaterThan(0))
        .body("find { it.id == 11 }.userCount", greaterThan(0));
  }

  /**
   * {@code HookOverrideResource.list} and {@code .get} — {@code core.hook_tenant_override}, and
   * the door where making the read transactional nearly opened a hole instead of closing one.
   *
   * <p>Both methods take the tenant as a QUERY PARAMETER. The switch behind it used to be inert
   * twice over: it set {@code TenantContextFilter.CURRENT_TENANT}, which Hibernate's
   * {@code tenantFilter} had already read BY VALUE in the request filter, and never
   * {@code CurrentTenantHolder}, which is what binds the RLS GUC. A foreign read came back empty
   * and a foreign write failed the policy's implicit WITH CHECK — accidental confinement, but
   * confinement. Routing the switch through {@code TenantScope} made it real, and an earlier
   * draft of THIS test asserted a tenant-1 identity writing and reading tenant 11's override,
   * pinning the hole in as if it were the feature.
   *
   * <p>An override stores a Kotlin script the hub executes, and {@code lm_admin} is a tenant-level
   * role whose interceptor reads no tenant at all. So the parameter is bound to the caller, and
   * what this test pins is the refusal.
   */
  @Test
  void a_hook_override_is_visible_to_its_own_tenant() {
    String hookId = "rls-it-hook-" + System.nanoTime();
    RestAssured.given().headers(TestIdentity.tenantOnly(1)).contentType("application/json")
        .body("{\"hookId\":\"" + hookId + "\",\"tenantId\":1,\"script\":\"// rls it\"}")
        .post("/api/platform/hooks/overrides").then().statusCode(204);

    RestAssured.given().headers(TestIdentity.tenantOnly(1))
        .get("/api/platform/hooks/overrides?tenantId=1")
        .then().statusCode(200).body(containsString(hookId));

    RestAssured.given().headers(TestIdentity.tenantOnly(1))
        .get("/api/platform/hooks/overrides/" + hookId + "?tenantId=1")
        .then().statusCode(200).body("hookId", is(hookId));
  }

  /**
   * And another tenant's overrides are refused at the door, not left to RLS.
   *
   * <p>Read and WRITE both: the write is the one that would have installed a Kotlin script into
   * another tenant's hub. Each was verified against the ungated code — the read answered 200 with
   * tenant 11's row, the write answered 204.
   */
  @Test
  void another_tenants_overrides_are_refused_at_the_door() {
    RestAssured.given().headers(TestIdentity.tenantOnly(1))
        .get("/api/platform/hooks/overrides?tenantId=11")
        .then().statusCode(403);

    RestAssured.given().headers(TestIdentity.tenantOnly(1)).contentType("application/json")
        .body("{\"hookId\":\"rls-it-foreign\",\"tenantId\":11,\"script\":\"// not yours\"}")
        .post("/api/platform/hooks/overrides").then().statusCode(403);

    RestAssured.given().headers(TestIdentity.tenantOnly(1)).contentType("application/json")
        .body("{\"enabled\":false}")
        .post("/api/platform/hooks/hello-world/toggle?tenantId=11")
        .then().statusCode(403);
  }
}
