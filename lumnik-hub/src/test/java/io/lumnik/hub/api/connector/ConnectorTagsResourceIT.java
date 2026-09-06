package io.lumnik.hub.api.connector;

import io.lumnik.testkit.auth.TestIdentity;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConnectorTagsResourceIT {

  @Inject ConnectorRepository connectors;
  @Inject UserTransaction utx;

  private String createConnector(long tenantId, String name) throws Exception {
    return TenantContext.callAsSystem(() -> {
      utx.begin();
      Connector c = new Connector();
      c.setTenantId(tenantId);
      c.setName(name);
      c.setTypeId("hello-world");
      c.setConfig(Map.of("count", 1));
      c.setEnabled(true);
      connectors.persist(c);
      utx.commit();
      return c.getId().toString();
    });
  }

  @Test
  void get_tags_returns_empty_when_not_set() throws Exception {
    String id = createConnector(1L, "empty-tags-" + System.nanoTime());

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + id + "/tags")
        .then().statusCode(200).body("$", empty());
  }

  @Test
  void put_tags_replaces_list() throws Exception {
    String id = createConnector(1L, "set-tags-" + System.nanoTime());

    given().headers(TestIdentity.tenantAndUser(1))
        .contentType("application/json")
        .body(List.of("compta", "paie"))
        .when().put("/api/platform/connectors/" + id + "/tags")
        .then().statusCode(200).body("$", containsInAnyOrder("compta", "paie"));

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/platform/connectors/" + id + "/tags")
        .then().statusCode(200).body("$", containsInAnyOrder("compta", "paie"));
  }

  @Test
  void put_tags_rejects_invalid_format() throws Exception {
    String id = createConnector(1L, "bad-tags-" + System.nanoTime());

    given().headers(TestIdentity.tenantAndUser(1))
        .contentType("application/json")
        .body(List.of("UPPERCASE_BAD"))
        .when().put("/api/platform/connectors/" + id + "/tags")
        .then().statusCode(400);

    given().headers(TestIdentity.tenantAndUser(1))
        .contentType("application/json")
        .body(List.of("with space"))
        .when().put("/api/platform/connectors/" + id + "/tags")
        .then().statusCode(400);
  }

  @Test
  void put_tags_returns_404_for_unknown_connector() {
    given().headers(TestIdentity.tenantAndUser(1))
        .contentType("application/json")
        .body(List.of("compta"))
        .when().put("/api/platform/connectors/00000000-0000-0000-0000-000000000000/tags")
        .then().statusCode(404);
  }
}
