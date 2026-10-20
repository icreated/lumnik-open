package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;

/**
 * #1128 review: the data-tables door reads on the RLS-enforced {@code default} datasource. Under
 * the real {@code lumnik_app} role, a dynamic table set up exactly as ingestion sets it up (RLS on,
 * the writer's tenant policy, the app grant — {@link RlsDdl}) is counted and paged for the
 * caller's tenant: the role can read it, and another tenant's rows stay out.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class DataTablesRlsIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @Test
  void the_rls_role_counts_and_pages_the_callers_rows_only() throws Exception {
    String n = String.valueOf(System.nanoTime());
    String table = "t_tables_rls_" + n, mapping = "m-rls-" + n;
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("CREATE TABLE connector." + table + " (id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, code text)");
      st.execute(RlsDdl.enableRowLevelSecurity("connector", table));
      st.execute(RlsDdl.tenantIsolationPolicy("connector", table));
      st.execute(RlsDdl.grantAppDml("connector", table));
      st.execute("INSERT INTO connector." + table + " (tenant_id, code) VALUES "
          + "(1128001, 'A-1'), (1128001, 'A-2'), (1128002, 'B-1')");
      st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
          + "VALUES (1128001, '" + mapping + "', 'connector', '" + table + "', '{}')");
    }
    given().headers(TestIdentity.tenantAndUser(1_128_001))
        .get("/api/platform/data/tables")
        .then().statusCode(200)
        .body("find { it.mappingId == '" + mapping + "' }.rowCount", Matchers.equalTo(2));
    given().headers(TestIdentity.tenantAndUser(1_128_001))
        .get("/api/platform/data/tables/" + mapping + "/rows")
        .then().statusCode(200)
        .body("code", Matchers.contains("A-1", "A-2"));
  }
}
