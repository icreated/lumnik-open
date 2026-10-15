package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * #846, local half of the Export promise: the snapshot download carries the joined columns and
 * reads the lookup under the caller's RLS. S3 delivery and the receipt are #848.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityJoinExportIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @BeforeEach
  void seedAndApply() throws Exception {
    EntitySourceTableFixture.create(adminDs, "t_products", "v_productwithcategory",
        "m_product_id", "m_product_category_id", "name");
    EntitySourceTableFixture.create(adminDs, "t_product_categories", "v_productwithcategory",
        "m_product_category_id", "name", "description");
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','Hydraulic pumps'), (2,'C1','OtherTenantCat','x')");
      st.execute("INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A')");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(EntityJoinIT.MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void a_local_export_snapshot_carries_the_joined_columns_and_only_this_tenants_lookup() {
    given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/exports/entities/ProductWithCategory/download")
        .then().statusCode(200)
        .body(containsString("\"category_name\""))
        .body(containsString("\"category_description\""))
        .body(containsString("\"Pumps\""))
        .body(containsString("\"Hydraulic pumps\""))
        .body(not(containsString("OtherTenantCat")));
  }
}
