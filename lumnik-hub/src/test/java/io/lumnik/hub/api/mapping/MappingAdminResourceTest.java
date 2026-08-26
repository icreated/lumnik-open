package io.lumnik.hub.api.mapping;

import io.lumnik.hub.connector.mapping.MappingTemplateRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MappingAdminResourceTest {

  @Inject MappingTemplateRegistry templates;

  @Test
  void list_returns_registered_mapping() {
    String tid = "list-test-" + System.nanoTime();
    templates.register(tid, "id: " + tid + "\nversion: 1\ntarget: foo");

    given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .when().get("/api/platform/mappings")
      .then().statusCode(200)
        .body("findAll { it.id == '" + tid + "' }[0].id", notNullValue());
  }

  /** Tenant comes ONLY from the auth-resolved context — no tenant → 401. */
  @Test
  void list_without_tenant_in_context_returns_401() {
    given()
      .when().get("/api/platform/mappings")
      .then().statusCode(401);
  }

  @Test
  void get_returns_effective_yaml() {
    String tid = "get-test-" + System.nanoTime();
    templates.register(tid, "id: " + tid + "\nversion: 1\ntarget: foo");

    given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .when().get("/api/platform/mappings/" + tid)
      .then().statusCode(200)
        .body("id", org.hamcrest.Matchers.equalTo(tid));
  }

  @Test
  void get_raw_returns_template_yaml() {
    String tid = "raw-test-" + System.nanoTime();
    templates.register(tid, "id: " + tid + "\nversion: 1\ntarget: foo");

    given()
      .header("X-Tenant-Id", "1").header("X-User-Id", "test")
      .queryParam("raw", true)
      .when().get("/api/platform/mappings/" + tid)
      .then().statusCode(200)
        .body("yaml", org.hamcrest.Matchers.containsString(tid));
  }
}
