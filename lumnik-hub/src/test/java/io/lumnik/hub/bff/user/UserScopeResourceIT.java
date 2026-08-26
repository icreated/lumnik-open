package io.lumnik.hub.bff.user;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class UserScopeResourceIT {

  @Inject TenantRepository tenants;
  @Inject UserRepository users;
  @Inject UserTransaction utx;

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
