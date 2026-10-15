package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * #846: a join does not widen a métier scope. The entity's scopes bound the entity as a whole, the
 * joined columns included — a caller bound to another scope is refused with the uniform
 * {@code scope not granted}, and one bound to the right scope reads the enriched row. Real signed
 * JWTs (NoBypassOidcProfile), because a header identity cannot carry a scope binding.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class EntityJoinScopeIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  static final String MANIFEST = """
      kind: Entity
      name: ScopeJoinProduct
      match: [product_id]
      scopes: [catalogue]
      sources:
        - table: t_scopejoin_p
          map: { pid: product_id, cid: category_id }
      joins:
        - table: t_scopejoin_c
          from: category_id
          to: cid
          map: { name: category_name }
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_scopejoinproduct\"");
      st.execute("DROP TABLE IF EXISTS connector.t_scopejoin_p");
      st.execute("DROP TABLE IF EXISTS connector.t_scopejoin_c");
      st.execute("CREATE TABLE connector.t_scopejoin_p (tenant_id bigint NOT NULL DEFAULT 1, pid text, cid text)");
      st.execute("CREATE TABLE connector.t_scopejoin_c (tenant_id bigint NOT NULL DEFAULT 1, cid text, name text)");
      st.execute("GRANT SELECT ON connector.t_scopejoin_p, connector.t_scopejoin_c TO lumnik_app");
      st.execute("INSERT INTO connector.t_scopejoin_c (cid, name) VALUES ('C1', 'Pumps')");
      st.execute("INSERT INTO connector.t_scopejoin_p (pid, cid) VALUES ('P1', 'C1')");
    }
    given().config(YamlBody.CONFIG)
        .header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_admin"), null))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  @Test
  void a_caller_bound_to_another_scope_is_refused_on_the_joined_entity() {
    given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_user", "scope:magasin"), null))
        .get("/api/entities/scopejoinproduct")
        .then().statusCode(403)
        .body("error", equalTo("scope not granted"));
  }

  @Test
  void a_caller_bound_to_the_entity_scope_reads_the_enriched_row() {
    given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(Set.of("lm_user", "scope:catalogue"), null))
        .get("/api/entities/scopejoinproduct")
        .then().statusCode(200)
        .body("[0].category_name", equalTo("Pumps"));
  }
}
