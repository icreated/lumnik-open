package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * An entity read writes a timestamp the same text whatever zone the hub runs in (#1323): a column its
 * sources hold as a timestamp is one in the view, written as its UTC instant (#1325).
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityReadTimestampIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  static final String MANIFEST = """
      kind: Entity
      name: StampedOrder
      match: [order_no]
      sources:
        - table: t_stamp_erp
          map:
            no: order_no
            at: ordered_at
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_stampedorder\"");
      st.execute("DROP TABLE IF EXISTS connector.t_stamp_erp");
      st.execute("CREATE TABLE connector.t_stamp_erp (tenant_id bigint NOT NULL, \"no\" text, \"at\" timestamptz)");
      st.execute("ALTER TABLE connector.t_stamp_erp ENABLE ROW LEVEL SECURITY");
      st.execute("CREATE POLICY t_stamp_erp_iso ON connector.t_stamp_erp"
          + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
      st.execute("GRANT SELECT ON connector.t_stamp_erp TO lumnik_app");
      st.execute("INSERT INTO connector.t_stamp_erp VALUES (1,'SO-1','2026-07-01 00:00:00.000500+00')");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void aTimestampIsWrittenInUtc_whateverTheHubsZone() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/stampedorder?order_no=SO-1")
        .then().statusCode(200)
        .body("[0].ordered_at", equalTo("2026-07-01T00:00:00.000500Z"));
  }
}
