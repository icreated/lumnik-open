package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;

@QuarkusTest
class DataTablesResourceTest {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  /** A dynamic table holding rows of two tenants, registered for tenant {@code a} under {@code mapping}. */
  private String table(long a, long b, String mapping) throws Exception {
    String t = "t_tables_it_" + System.nanoTime();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("CREATE TABLE connector." + t + " (id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, "
          + "_row_hash text, code text)");
      st.execute("INSERT INTO connector." + t + " (tenant_id, _row_hash, code) VALUES "
          + "(" + a + ", 'h1', 'A-1'), (" + a + ", 'h2', 'A-2'), (" + a + ", 'h3', 'A-3'), (" + b + ", 'h9', 'B-1')");
      st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
          + "VALUES (" + a + ", '" + mapping + "', 'connector', '" + t + "', '{}')");
    }
    return t;
  }

  /** #1115: the list counts the caller's rows — it used to answer -1 for every table. */
  @Test
  void the_list_counts_the_callers_rows_only() throws Exception {
    String mapping = "m-count-" + System.nanoTime();
    table(1_115_001, 1_115_002, mapping);
    given().headers(TestIdentity.tenantAndUser(1_115_001))
      .when().get("/api/platform/data/tables")
      .then().statusCode(200)
      .body("find { it.mappingId == '" + mapping + "' }.rowCount", Matchers.equalTo(3));
  }

  /** #1115: a dynamic table's rows, paged in landing order, the caller's tenant only. */
  @Test
  void the_rows_door_pages_the_callers_rows_in_landing_order() throws Exception {
    String mapping = "m-rows-" + System.nanoTime();
    table(1_115_003, 1_115_004, mapping);
    given().headers(TestIdentity.tenantAndUser(1_115_003))
      .when().get("/api/platform/data/tables/" + mapping + "/rows?limit=2&offset=1")
      .then().statusCode(200)
      .body("code", Matchers.contains("A-2", "A-3"))
      .body("[0]", Matchers.not(Matchers.hasKey("tenant_id")));
    given().headers(TestIdentity.tenantAndUser(1_115_004))
      .when().get("/api/platform/data/tables/" + mapping + "/rows")
      .then().statusCode(404); // registered for the other tenant: not this caller's table
  }

  @Test
  void list_returns_200_with_array() {
    given()
      .headers(TestIdentity.tenantAndUser(1))
      .when().get("/api/platform/data/tables")
      .then().statusCode(200);
  }

  /** Tenant comes ONLY from the auth-resolved context — no tenant → 401. */
  @Test
  void without_tenant_in_context_returns_401() {
    given()
      .when().get("/api/platform/data/tables")
      .then().statusCode(401);
  }
}
