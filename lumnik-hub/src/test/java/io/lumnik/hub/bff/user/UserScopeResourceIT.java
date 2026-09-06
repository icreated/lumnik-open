package io.lumnik.hub.bff.user;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class UserScopeResourceIT {

  @Inject TenantRepository tenants;
  @Inject UserRepository users;
  @Inject UserTransaction utx;

  /**
   * `lm scope` on a real deployment sends no X-User-Id — it authenticates with a Bearer token — so
   * the door met a null id, called findById(null) and answered a 500 with an error id. The PUT one
   * method below already guarded it; the GET did not. Both now take the user from the authenticated
   * context, and a request that carries no identity at all gets the honest 401 RequireTenant gives.
   */
  @Test
  void get_without_any_identity_is_401_not_500() {
    given().header("X-Tenant-Id", "1")
        .when().get("/api/platform/users/me/scope")
        .then().statusCode(401);
  }

  /**
   * `lm scope` answered "(no default scope)" on a hub whose user table was EMPTY: the GET rendered
   * "no row" and "no scope set" as the same {@code null}. The PUT one method down already answered
   * 404 for the same state — with no body, so lm printed "no details from the server". Both doors
   * now give the same 404 with the same sentence, the one that would have saved the quarter-hour.
   */
  @Test
  void a_user_the_hub_has_no_row_for_is_404_on_get_as_on_put_and_says_so() {
    var nobody = TestIdentity.tenantAndUser(1, 987654321L);

    given().headers(nobody)
        .when().get("/api/platform/users/me/scope")
        .then().statusCode(404)
        .body("error", containsString("no user record"))
        .body("error", containsString("987654321"));

    given().headers(nobody).contentType("application/json")
        .body("{\"defaultScope\":\"compta\"}")
        .when().put("/api/platform/users/me/scope")
        .then().statusCode(404)
        .body("error", containsString("no user record"));
  }

  @Test
  void put_then_get_returns_value() throws Exception {
    String suffix = String.valueOf(System.nanoTime());
    Long[] ids = TenantContext.callAsSystem(() -> {
      utx.begin();
      Tenant t = new Tenant("SCOPE-" + suffix, "Scope Test Tenant " + suffix);
      tenants.persist(t);
      User u = new User(t.getId(), "scope-user-" + suffix, "Scope User");
      users.persist(u);
      utx.commit();
      return new Long[]{t.getId(), u.getId()};
    });
    Long tenantId = ids[0];
    Long uid = ids[1];

    given().header("X-Tenant-Id", tenantId.toString()).header("X-User-Id", uid.toString())
        .contentType("application/json")
        .body("{\"defaultScope\":\"compta\"}")
        .when().put("/api/platform/users/me/scope")
        .then().statusCode(204);

    given().header("X-Tenant-Id", tenantId.toString()).header("X-User-Id", uid.toString())
        .when().get("/api/platform/users/me/scope")
        .then().statusCode(200).body("defaultScope", equalTo("compta"));
  }
}
