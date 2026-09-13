package io.lumnik.hub.entity;

import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityApplyIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject EntityRegistry registry;

  // RestAssured doesn't know application/yaml natively; treat it as TEXT.
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
      """;

  @BeforeEach
  void createSourceTable() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'customer'");
      st.execute("DROP VIEW IF EXISTS connector.\"v_customer\"");
      st.execute("DROP TABLE IF EXISTS connector.t_cust_src");
      st.execute("CREATE TABLE connector.t_cust_src ("
          + "tenant_id bigint NOT NULL, \"ROW_ID\" text, \"NAME\" text, \"OU_TYPE_CD\" text)");
      st.execute("GRANT SELECT ON connector.t_cust_src TO lumnik_app");
    }
  }

  /**
   * A manifest whose source tables were never created (connectors not run yet) is a
   * PREDICTABLE state on a fresh install — it must refuse honestly, naming the missing
   * relation and the gesture, never escape as an anonymous 500 (seen live on the first
   * Windows install: 'Error id 0df2fac5…' for a missing connector.cust_siebel).
   */
  @Test
  void apply_over_missing_source_tables_refuses_honestly() {
    String orphan = """
        kind: Entity
        name: Orphan
        match: [id]
        sources:
          - table: t_never_ingested
            map:
              ROW_ID: id
        """;
    given().config(YamlBody.CONFIG)
        .headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(orphan)
        .post("/api/entities/apply")
        .then().statusCode(409)
        .body("error", Matchers.containsString("t_never_ingested"))
        .body("error", Matchers.containsString("does not exist"))
        .body("error", Matchers.containsString("run the source connectors"));
  }

  @Test
  void apply_creates_the_view() throws Exception {
    given().config(YamlBody.CONFIG)
        .headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply")
        .then().statusCode(200)
        .body("name", Matchers.equalTo("Customer"))
        .body("view", Matchers.equalTo("connector.\"v_customer\""));

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT count(*) FROM information_schema.views "
                 + "WHERE table_schema = 'connector' AND table_name = 'v_customer'")) {
      rs.next();
      assertThat(rs.getLong(1)).isEqualTo(1L);
    }
  }

  @Test
  void apply_persists_manifest_to_db() throws Exception {
    given().config(YamlBody.CONFIG)
        .headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply")
        .then().statusCode(200);

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT yaml_text FROM connector.entity_manifest WHERE name = 'customer'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("yaml_text")).isEqualTo(MANIFEST);
    }
  }

  @Test
  void registry_reloads_manifests_from_db() throws Exception {
    // Seed directly into DB (bypass the apply endpoint)
    try (Connection c = adminDs.getConnection();
         var ps = c.prepareStatement(
             "INSERT INTO connector.entity_manifest (name, yaml_text) VALUES (?, ?)")) {
      ps.setString(1, "customer");
      ps.setString(2, MANIFEST);
      ps.executeUpdate();
    }

    registry.loadFromDb();

    assertThat(registry.find("Customer")).isNotNull();
    assertThat(registry.find("customer")).isNotNull();
  }

  @Test
  void loadFromDb_rejects_yaml_with_divergent_name() throws Exception {
    // DB key = 'customer' but YAML declares name: Supplier — data integrity violation
    String divergentYaml = MANIFEST.replace("name: Customer", "name: Supplier");
    try (Connection c = adminDs.getConnection();
         var ps = c.prepareStatement(
             "INSERT INTO connector.entity_manifest (name, yaml_text) VALUES (?, ?)")) {
      ps.setString(1, "customer");
      ps.setString(2, divergentYaml);
      ps.executeUpdate();
    }
    assertThatThrownBy(() -> registry.loadFromDb())
        .isInstanceOf(RuntimeException.class);
  }

  /**
   * Every refusal {@code docs/entities.md} tables, with the message it promises — not merely the
   * status. Asserting only {@code statusCode(400)} is what let the door ship a bodyless refusal:
   * {@code EntityManifestParser} pins the RULES, and a rule and its DELIVERY are two claims.
   *
   * <p>Each case names the rule and a fragment of the exact text, so a reworded refusal fails here
   * rather than silently breaking the documented contract.
   */
  static Stream<Arguments> refusals() {
    return Stream.of(
        Arguments.of("wrong kind",
            MANIFEST.replace("kind: Entity", "kind: Connector"),
            "kind must be 'Entity'"),
        Arguments.of("name is not an identifier",
            MANIFEST.replace("name: Customer", "name: my-customer"),
            "name must be a simple identifier"),
        Arguments.of("no match column",
            MANIFEST.replace("match: [id]", "match: []"),
            "match must declare at least one column"),
        Arguments.of("duplicate match column",
            MANIFEST.replace("match: [id]", "match: [id, ID]"),
            "match declares a duplicate column"),
        Arguments.of("no sources",
            """
            kind: Entity
            name: Customer
            match: [id]
            sources: []
            """,
            "sources must have at least one source"),
        Arguments.of("reserved canonical column",
            MANIFEST.replace("NAME: name", "NAME: _src"),
            "is reserved"),
        Arguments.of("duplicate canonical column in one source",
            MANIFEST.replace("OU_TYPE_CD: segment", "OU_TYPE_CD: name"),
            "maps duplicate canonical column"),
        Arguments.of("source does not map the match column in the same case",
            MANIFEST.replace("ROW_ID: id", "ROW_ID: Id"),
            "does not map the match column"),
        Arguments.of("scope is not a legal role name",
            MANIFEST.replace("match: [id]", "match: [id]\nscopes: [Clients]"),
            "scope must be lowercase"),
        Arguments.of("grounding names a column that does not exist",
            MANIFEST.replace("match: [id]", "match: [id]\ncolumns:\n  NAME:\n    label: Nom"),
            "columns references unknown canonical column"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusals")
  void refusal_carries_its_message(String rule, String badManifest, String expected) {
    given().config(YamlBody.CONFIG)
        .headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(badManifest)
        .post("/api/entities/apply")
        .then().statusCode(400)
        .contentType(ContentType.JSON)
        .body("error", Matchers.containsString(expected));
  }
}
