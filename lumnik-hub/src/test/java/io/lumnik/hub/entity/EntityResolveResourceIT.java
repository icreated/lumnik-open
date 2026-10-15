package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static io.restassured.RestAssured.given;

/**
 * #620 — the explicit door {@code POST /api/entities/{name}/resolve} (design doc §7's second of
 * two triggers): a manual replay pass for an operator who does not want to wait for the next
 * connector run.
 */
@QuarkusTest
class EntityResolveResourceIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject EntityRegistry registry;

  private static final String TABLE = "t_res_resolve_it_erp";

  private static final String RESOLUTION_MANIFEST = """
      kind: Entity
      name: ResResolveIt
      sources:
        - table: t_res_resolve_it_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_resolve_it_erp: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
      """;

  private static final String MATCH_MANIFEST = """
      kind: Entity
      name: ResResolveItMatch
      match: [customer_code]
      sources:
        - table: t_res_resolve_it_match
          map:
            CODE: customer_code
      """;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector." + TABLE);
      st.execute("CREATE TABLE connector." + TABLE + " ("
          + "id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, _run_id uuid, "
          + "\"CUSTOMER_ID\" text, \"EMAIL_COL\" text)");
      st.execute("DELETE FROM core.entity_identity WHERE entity_type = 'resresolveit'");
    }
    registry.put(new EntityManifestParser().parse(RESOLUTION_MANIFEST));
    registry.put(new EntityManifestParser().parse(MATCH_MANIFEST));
  }

  @Test
  void resolve_of_an_unknown_entity_is_a_404() {
    given().headers(TestIdentity.tenantAndUser(1))
        .post("/api/entities/DoesNotExist/resolve")
        .then().statusCode(404)
        .body("error", Matchers.containsString("DoesNotExist"));
  }

  @Test
  void resolve_of_a_match_only_entity_is_a_400() {
    given().headers(TestIdentity.tenantAndUser(1))
        .post("/api/entities/ResResolveItMatch/resolve")
        .then().statusCode(400)
        .body("error", Matchers.containsString("resolution"));
  }

  @Test
  void resolve_replays_every_source_row_and_reports_the_outcome_tally() throws Exception {
    long tenant = 98_101;
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector." + TABLE + " (tenant_id, \"CUSTOMER_ID\", \"EMAIL_COL\") "
                 + "VALUES (?, 'door-1', 'door1@example.com')")) {
      ps.setLong(1, tenant);
      ps.executeUpdate();
    }

    given().headers(TestIdentity.tenantAndUser(tenant))
        .post("/api/entities/ResResolveIt/resolve")
        .then().statusCode(200)
        .body("rowsSeen", Matchers.equalTo(1))
        .body("resolved", Matchers.equalTo(1))
        .body("ambiguous", Matchers.equalTo(0))
        .body("unresolvedNoRule", Matchers.equalTo(0))
        .body("unresolvedProvider", Matchers.equalTo(0));
  }
}
