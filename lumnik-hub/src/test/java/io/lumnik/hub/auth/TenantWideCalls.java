package io.lumnik.hub.auth;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The calls of the {@code @TenantWide} ITs, on {@link NoBypassOidcProfile}: an admin with or without a scope, and the
 * uniform refusal a bound one gets. One copy for the tenant-wide ITs.
 */
public final class TenantWideCalls {

  public static final Set<String> ADMIN = Set.of("lm_admin", "lm_integrator");

  private TenantWideCalls() {}

  /** A JSON request as a caller of tenant 1 holding exactly {@code roles}. */
  public static RequestSpecification as(Set<String> roles) {
    return given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(roles, null))
        .header("Content-Type", "application/json");
  }

  /** An admin bound to {@code scope:magasin}, holding {@code moreRoles} too. */
  public static RequestSpecification bound(String... moreRoles) {
    Set<String> roles = new HashSet<>(ADMIN);
    Collections.addAll(roles, moreRoles);
    roles.add("scope:magasin");
    return as(roles);
  }

  public static void refused(Response r) {
    r.then().statusCode(403).body("error", equalTo("scope not granted"));
  }
}
