package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.lumnik.hub.auth.TenantWideCalls.as;
import static io.lumnik.hub.auth.TenantWideCalls.bound;
import static io.lumnik.hub.auth.TenantWideCalls.refused;

/**
 * #1992: the doors whose subject is the whole tenant, or wider — its secrets, its event dead letters, its users, roles
 * and organisations, the tenants themselves, the RAG corpus every tenant shares — refuse a caller bound to a scope,
 * before any lookup, as the hook and subscription doors do. {@code DoorInventoryTest} keeps the list; this is
 * the wire.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class TenantWideDoorsIT {

  /**
   * The writes carry bodies and names that would write nothing were the filter gone (a 400 or a 404): a regression
   * fails here without leaving a secret or a user in the application every NoBypassOidcProfile test shares.
   */
  @Test
  void a_bound_admin_is_refused_the_secrets() {
    refused(bound().get("/api/platform/secrets"));
    refused(bound().get("/api/platform/secrets/NO_SUCH_SECRET"));
    refused(bound().body("{}").post("/api/platform/secrets"));
    refused(bound().body("{}").put("/api/platform/secrets/NO_SUCH_SECRET"));
    refused(bound().delete("/api/platform/secrets/NO_SUCH_SECRET"));
  }

  @Test
  void a_bound_admin_is_refused_the_event_dead_letters() {
    refused(bound().get("/api/platform/events/dead-letters"));
    refused(bound().post("/api/platform/events/dead-letters/999999999/replay"));
  }

  /** The reindex rebuilds the corpus every tenant shares; the status counts the caller's own scopes (#1895). */
  @Test
  void a_bound_admin_is_refused_the_reindex_and_keeps_the_status() {
    refused(bound().post("/api/platform/rag/reindex"));
    bound().get("/api/platform/rag/status").then().statusCode(200);
  }

  @Test
  void a_bound_admin_is_refused_the_tenant_identity_doors() {
    refused(bound().get("/api/tenants/1/users"));
    refused(bound().body("{}").post("/api/tenants/1/users"));
    refused(bound().get("/api/tenants/1/users/1/roles"));
    refused(bound().get("/api/tenants/1/roles"));
    refused(bound().get("/api/tenants/1/roles/1/orgs"));
    refused(bound().get("/api/tenants/1/orgs"));
  }

  @Test
  void a_bound_superadmin_is_refused_the_tenants() {
    refused(bound("lm_superadmin").get("/api/tenants"));
    refused(bound("lm_superadmin").get("/api/tenants/1"));
    refused(bound("lm_superadmin").get("/api/platform/admin/tenants"));
  }

  /** On a door that also reconciles the URL's tenant, the bound caller gets the scope refusal, whichever tenant. */
  @Test
  void a_bound_admin_gets_the_scope_refusal_on_another_tenants_url() {
    refused(bound().get("/api/tenants/2/users"));
  }

  /**
   * The control: the same doors answer an unbound caller (tenant 1 is identity's seeded default). The reindex is left
   * to RagResourceIT: here it would rebuild the corpus of the application this profile shares.
   */
  @Test
  void an_unbound_admin_keeps_them() {
    Set<String> unbound = Set.of("lm_admin", "lm_integrator", "lm_superadmin");
    for (String door : List.of("/api/platform/secrets", "/api/platform/events/dead-letters", "/api/tenants/1/users",
        "/api/tenants/1/orgs", "/api/tenants", "/api/platform/admin/tenants")) {
      as(unbound).get(door).then().statusCode(200);
    }
  }
}
