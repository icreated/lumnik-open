package io.lumnik.hub.entity;

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
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Two tenants, two source tables, the SAME customer_code in both tenants. Proves the fused
 * security_invoker view shows each tenant only its own rows from each source — RLS survives
 * the UNION ALL branches. This is the v1 jewel.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityFusionRlsIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  static final String MANIFEST = """
      kind: Entity
      name: RlsFuseCustomer
      match: [customer_code]
      sources:
        - table: t_rlsfuse_erp
          map:
            cust_id: customer_code
            cust_name: name
        - table: t_rlsfuse_csv
          map:
            code: customer_code
            email: email
      """;

  static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config().encoderConfig(
      EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_rlsfusecustomer\"");
      st.execute("DROP TABLE IF EXISTS connector.t_rlsfuse_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_rlsfuse_csv");
      st.execute("CREATE TABLE connector.t_rlsfuse_erp (tenant_id bigint NOT NULL, \"cust_id\" text, \"cust_name\" text)");
      st.execute("CREATE TABLE connector.t_rlsfuse_csv (tenant_id bigint NOT NULL, \"code\" text, \"email\" text)");
      for (String t : new String[]{"t_rlsfuse_erp", "t_rlsfuse_csv"}) {
        st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
        st.execute("CREATE POLICY " + t + "_iso ON connector." + t
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app");
      }
      // SAME customer_code 'C-1' in both tenants — only RLS distinguishes them.
      st.execute("INSERT INTO connector.t_rlsfuse_erp VALUES (1,'C-1','Alice-T1'), (2,'C-1','Zoe-T2')");
      st.execute("INSERT INTO connector.t_rlsfuse_csv VALUES (1,'C-1','alice@t1'), (2,'C-1','zoe@t2')");
    }
    given().config(YAML_CFG).header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void tenant1_sees_only_its_fused_row() {
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .get("/api/entities/rlsfusecustomer")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Alice-T1"))
        .body("[0].email", equalTo("alice@t1"))
        .body("[0].customer_code", equalTo("C-1"));
  }

  @Test
  void tenant2_sees_only_its_fused_row() {
    given().header("X-Tenant-ID", "2").header("X-User-ID", "2")
        .get("/api/entities/rlsfusecustomer")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Zoe-T2"))
        .body("[0].email", equalTo("zoe@t2"))
        .body("[0].customer_code", equalTo("C-1"));
  }
}
