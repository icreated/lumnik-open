package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * #1523: a view written over an entity that was re-applied without one of its columns. The apply refreshes every card
 * that lists the entity (#1538), but best-effort: a card whose refresh failed still names the column, so the view
 * resolves, and the read used to fail in SQL: a bare 500. Every render door (the list, a page of it, one record's
 * detail) refuses with a 400 naming the column and the table.
 */
@QuarkusTest
class ViewOverAChangedEntityIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject EntitySchemaBuilder entityCards;
  @Inject SchemaCardStore cards;

  static final String ENTITY = "kind: Entity\nname: VceCustomer\nmatch: [customer_code]\nscopes: [vce]\nsources:\n"
      + "  - table: t_vce_customer\n    map: { customer_code: customer_code, segment: segment }\n";

  static final String VIEW = """
      apiVersion: apps.lumnik.io/v1
      kind: View
      metadata: { name: vce-view }
      spec:
        scope: vce
        table: connector.v_vcecustomer
        columns: [customer_code, segment]
      """;

  static final String REFUSAL =
      "column 'segment' is not in connector.v_vcecustomer — the entity changed since this view was written";

  private void exec(String... sql) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }

  private static ValidatableResponse applyEntity(String manifest) {
    return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body(manifest).post("/api/entities/apply").then();
  }

  private static ValidatableResponse post(String path) {
    return given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(VIEW)
        .when().post(path).then().log().ifValidationFails();
  }

  /**
   * The entity with both columns, the card written from it, then the entity re-applied elsewhere without `segment` —
   * and the card written back as it was, the state a failed refresh leaves.
   */
  @BeforeEach
  void anEntityThatLostAColumnUnderAView() throws Exception {
    exec("DROP VIEW IF EXISTS connector.\"v_vcecustomer\"",
        "DROP TABLE IF EXISTS connector.t_vce_customer",
        "CREATE TABLE connector.t_vce_customer (tenant_id bigint NOT NULL, customer_code text, segment text)",
        "DO $$ BEGIN GRANT SELECT ON connector.t_vce_customer TO lumnik_app;"
            + " GRANT SELECT ON connector.t_vce_customer TO lumnik_readonly; EXCEPTION WHEN OTHERS THEN NULL; END $$",
        "INSERT INTO connector.t_vce_customer VALUES (1, 'C-1', 'GOLD'), (1, 'C-2', 'SILVER')",
        "DELETE FROM connector.entity_manifest WHERE name = 'vcecustomer'");
    applyEntity(ENTITY).statusCode(200);
    SchemaCard before = new SchemaCard("vce", entityCards.resolve("vce"));
    cards.save(1L, "registry", before);
    post("/api/views/render").statusCode(200).body("rows[0].segment", equalTo("GOLD"));

    applyEntity(ENTITY.replace(", segment: segment", "").replace("scopes: [vce]", "scopes: [vce2]")).statusCode(200);
    cards.save(1L, "registry", before);
  }

  @Test
  void theListRender_refusesNamingTheColumnAndTheTable() {
    post("/api/views/render").statusCode(400).body("error", equalTo(REFUSAL));
  }

  @Test
  void aPageOfIt_refusesTheSame() {
    post("/api/views/render?pageSize=10").statusCode(400).body("error", equalTo(REFUSAL));
  }

  @Test
  void oneRecordsDetail_refusesTheSame() {
    post("/api/views/detail?key=C-1").statusCode(400).body("error", equalTo(REFUSAL));
  }
}
