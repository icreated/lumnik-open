package io.lumnik.hub.entity;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Two source tables (erp, csv) fused on customer_code, single tenant. Both map `name` so we can
 * prove agreement, gap-fill, and a cross-source conflict (representative = erp by listing order).
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityFusionIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  static final String MANIFEST = """
      kind: Entity
      name: FusedCustomer
      match: [customer_code]
      sources:
        - table: t_fuse_erp
          map:
            cust_id: customer_code
            cust_name: name
        - table: t_fuse_csv
          map:
            code: customer_code
            cname: name
            email: email
            ville: city
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_fusedcustomer\"");
      st.execute("DROP TABLE IF EXISTS connector.t_fuse_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_fuse_csv");
      st.execute("CREATE TABLE connector.t_fuse_erp (tenant_id bigint NOT NULL, \"cust_id\" text, \"cust_name\" text)");
      st.execute("CREATE TABLE connector.t_fuse_csv (tenant_id bigint NOT NULL, \"code\" text, \"cname\" text, \"email\" text, \"ville\" text)");
      for (String t : new String[]{"t_fuse_erp", "t_fuse_csv"}) {
        st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
        st.execute("CREATE POLICY " + t + "_iso ON connector." + t
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app");
      }
      // C-100: name AGREES ('Jean Dupont' in both); email+city only in csv (gap-fill) -> _conflicts {}
      st.execute("INSERT INTO connector.t_fuse_erp VALUES (1,'C-100','Jean Dupont')");
      st.execute("INSERT INTO connector.t_fuse_csv VALUES (1,'C-100','Jean Dupont','jd@x.fr','Paris')");
      // C-200: name CONFLICTS (erp 'Marie' vs csv 'MARIE A.'); representative = erp (listing order)
      st.execute("INSERT INTO connector.t_fuse_erp VALUES (1,'C-200','Marie')");
      st.execute("INSERT INTO connector.t_fuse_csv VALUES (1,'C-200','MARIE A.','m@x.fr','Lyon')");
      // C-300: exists ONLY in csv (no erp row) -> pure gap-fill, the entity is still fused -> _conflicts {}
      st.execute("INSERT INTO connector.t_fuse_csv VALUES (1,'C-300','Solo','solo@x.fr','Nice')");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void gap_fill_and_agreement_yield_empty_conflicts() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/fusedcustomer?customer_code=C-100")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Jean Dupont"))   // agreement
        .body("[0].email", equalTo("jd@x.fr"))       // gap-fill from csv
        .body("[0].city", equalTo("Paris"))          // gap-fill from csv
        .body("[0]._conflicts", equalTo("{}"));  // _conflicts is a JSON string (::text cast in EntityReadResource), not an object
  }

  @Test
  void conflict_keeps_erp_representative_and_surfaces_the_ledger() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/fusedcustomer?customer_code=C-200")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Marie"))          // representative = erp (listing order, _ord 0)
        .body("[0].email", equalTo("m@x.fr"))
        .body("[0].city", equalTo("Lyon"))
        .body("[0]._conflicts", containsString("name"))
        .body("[0]._conflicts", containsString("Marie"))
        .body("[0]._conflicts", containsString("MARIE A."))
        .body("[0]._conflicts", containsString("t_fuse_erp"))
        .body("[0]._conflicts", containsString("t_fuse_csv"));
  }

  @Test
  void entity_present_in_only_one_source_is_still_fused() {
    // C-300 has no ERP row at all; the CSV row alone produces the fused entity with no conflict.
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/fusedcustomer?customer_code=C-300")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Solo"))
        .body("[0].email", equalTo("solo@x.fr"))
        .body("[0].city", equalTo("Nice"))
        .body("[0]._conflicts", equalTo("{}"));
  }
}
