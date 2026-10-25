package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * #1115 security review: a dynamic table's raw rows are bounded by the scope of the
 * connector that landed them, as an entity's rows are by the entity's — a caller bound to another
 * scope neither lists the table nor reads it (the uniform {@code scope not granted}). Real signed
 * JWTs (NoBypassOidcProfile), because a header identity cannot carry a scope binding.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class DataTablesScopeIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  /** A table landed by a connector tagged {@code catalogue}, in tenant 1 (the tokens' tenant). */
  private String seed() throws Exception {
    String n = String.valueOf(System.nanoTime());
    String table = "t_scope_tables_" + n, mapping = "m-scope-" + n;
    UUID connector = UUID.randomUUID();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('"
          + connector + "', 1, 'scope-src-" + n + "', 'csv-file', '{}'::jsonb, true, ARRAY['catalogue'])");
      st.execute("CREATE TABLE connector." + table + " (id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, code text)");
      st.execute("INSERT INTO connector." + table + " (tenant_id, code) VALUES (1, 'P1')");
      st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
          + "VALUES (1, '" + mapping + "', 'connector', '" + table + "', '{}', '" + connector + "')");
    }
    return mapping;
  }

  private static String bearer(String scope) {
    return "Bearer " + NoBypassOidcProfile.token(Set.of("lm_integrator", "scope:" + scope), null);
  }

  @Test
  void a_caller_bound_to_another_scope_neither_lists_nor_reads_the_table() throws Exception {
    String mapping = seed();
    given().header("Authorization", bearer("magasin"))
        .get("/api/platform/data/tables")
        .then().statusCode(200)
        .body("mappingId", not(hasItem(mapping)));
    given().header("Authorization", bearer("magasin"))
        .get("/api/platform/data/tables/" + mapping + "/rows")
        .then().statusCode(403)
        .body("error", equalTo("scope not granted"));
  }

  @Test
  void a_table_another_scopes_connector_also_writes_is_neither_listed_nor_read() throws Exception {
    // #1186: each connector writing one table registers it; the table holds every writer's rows
    String mapping = seed();
    UUID other = UUID.randomUUID();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('"
          + other + "', 1, 'scope-other-" + other + "', 'csv-file', '{}'::jsonb, true, ARRAY['magasin'])");
      st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
          + "SELECT tenant_id, mapping_id, table_schema, table_name, schema_inferred, '" + other + "' "
          + "FROM connector.dynamic_table WHERE mapping_id = '" + mapping + "'");
    }
    given().header("Authorization", bearer("catalogue"))
        .get("/api/platform/data/tables")
        .then().statusCode(200)
        .body("mappingId", not(hasItem(mapping)));
    given().header("Authorization", bearer("catalogue"))
        .get("/api/platform/data/tables/" + mapping + "/rows")
        .then().statusCode(403)
        .body("error", equalTo("scope not granted"));
  }

  @Test
  void a_table_two_mappings_write_lists_each_mapping_once() throws Exception {
    // #1200 review: a csv and a jdbc connector can land in one t_<name> under two mapping ids
    String mapping = seed();
    String other = "jdbc:" + mapping;
    UUID twin = UUID.randomUUID();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags) VALUES ('"
          + twin + "', 1, 'scope-twin-" + twin + "', 'jdbc-generic', '{}'::jsonb, true, ARRAY['catalogue'])");
      // the twin registers the table under its own mapping, and under the first one too: two writers of one mapping
      for (String m : List.of(other, mapping)) {
        st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
            + "SELECT tenant_id, '" + m + "', table_schema, table_name, schema_inferred, '" + twin + "' "
            + "FROM connector.dynamic_table WHERE mapping_id = '" + mapping + "' AND connector_id <> '" + twin + "'");
      }
    }
    given().header("Authorization", bearer("catalogue"))
        .get("/api/platform/data/tables")
        .then().statusCode(200)
        .body("findAll { it.mappingId == '" + mapping + "' }.size()", equalTo(1))
        .body("findAll { it.mappingId == '" + other + "' }.size()", equalTo(1));
  }

  @Test
  void a_caller_bound_to_the_connectors_scope_lists_and_reads_it() throws Exception {
    String mapping = seed();
    given().header("Authorization", bearer("catalogue"))
        .get("/api/platform/data/tables")
        .then().statusCode(200)
        .body("mappingId", hasItem(mapping));
    given().header("Authorization", bearer("catalogue"))
        .get("/api/platform/data/tables/" + mapping + "/rows")
        .then().statusCode(200)
        .body("[0].code", equalTo("P1"));
  }
}
