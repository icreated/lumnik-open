package io.lumnik.hub.entity;

import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import io.restassured.path.json.JsonPath;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityReadIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  static final String MANIFEST = """
      kind: Entity
      name: Customer
      match: [id]
      sources:
        - table: t_cust_src
          map:
            ROW_ID: id
            NAME: name
            OU_TYPE_CD: segment
            CREDIT_LIMIT: credit_limit
      """;

  static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config().encoderConfig(
      EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_customer\"");
      st.execute("DROP TABLE IF EXISTS connector.t_cust_src");
      st.execute("CREATE TABLE connector.t_cust_src ("
          + "tenant_id bigint NOT NULL, \"ROW_ID\" text, \"NAME\" text, \"OU_TYPE_CD\" text,"
          + " \"CREDIT_LIMIT\" bigint)");
      st.execute("ALTER TABLE connector.t_cust_src ENABLE ROW LEVEL SECURITY");
      st.execute("CREATE POLICY cust_src_iso ON connector.t_cust_src "
          + "USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
      st.execute("GRANT SELECT ON connector.t_cust_src TO lumnik_app");
      st.execute("INSERT INTO connector.t_cust_src VALUES "
          + "(1,'r1','Alice','GOLD',5000),(1,'r2','Bob','SILVER',1000),(2,'r9','Zoe','GOLD',9000)");
    }
    given().config(YAML_CFG)
        .headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void tenant1_sees_only_its_rows_with_renamed_columns() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer")
        .then().statusCode(200)
        .body("size()", equalTo(2))
        .body("id", hasItems("r1", "r2"))
        .body("name", hasItems("Alice", "Bob"))
        .body("segment", hasItems("GOLD", "SILVER"));
  }

  @Test
  void tenant2_is_isolated() {
    given().headers(TestIdentity.tenantAndUser(2, 2))
        .get("/api/entities/customer")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("name", hasItems("Zoe"));
  }

  @Test
  void filters_by_canonical_column() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer?segment=GOLD")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("name", hasItems("Alice"));
  }

  @Test
  void paginates_with_limit() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer?limit=1")
        .then().statusCode(200)
        .body("size()", equalTo(1));
  }

  @Test
  void unknown_entity_is_404() throws Exception {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/nope")
        .then().statusCode(404);

    // A refused read must never be recorded as a successful access.
    try (Connection c = adminDs.getConnection();
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT count(*) FROM core.audit_outbox WHERE entity_key = 'nope' AND operation = 'READ'")) {
      rs.next();
      assertThat(rs.getLong(1)).as("a 404 must not write a READ audit row").isZero();
    }
  }

  @Test
  void filters_by_numeric_column() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer?credit_limit=5000")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("name", hasItems("Alice"));
  }

  @Test
  void paginates_deterministically() {
    // Fetch page 0 (limit=1, offset=0) and page 1 (limit=1, offset=1)
    JsonPath page0 = given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer?limit=1&offset=0")
        .then().statusCode(200).body("size()", equalTo(1))
        .extract().jsonPath();
    JsonPath page1 = given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer?limit=1&offset=1")
        .then().statusCode(200).body("size()", equalTo(1))
        .extract().jsonPath();

    String id0 = page0.getString("[0].id");
    String id1 = page1.getString("[0].id");

    // Pages must not overlap
    assertThat(id0).isNotEqualTo(id1);

    // Together they must cover exactly the 2 tenant-1 rows
    JsonPath full = given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer")
        .then().statusCode(200).body("size()", equalTo(2))
        .extract().jsonPath();
    List<String> allIds = full.getList("id", String.class);

    // The two pages must each appear in the full result (deterministic: run twice must match)
    assertThat(allIds).contains(id0, id1);

    // Re-run page 0 to confirm order is stable across requests
    JsonPath page0Again = given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customer?limit=1&offset=0")
        .then().statusCode(200).extract().jsonPath();
    assertThat(page0Again.getString("[0].id")).isEqualTo(id0);
  }
}
