package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * #846 against a real database: a join enriches, never multiplies, never guesses, never crosses a
 * tenant. RLS is enforced (RlsEnforcedProfile) because the lookup is read under the caller's own
 * policy — the whole point of a security_invoker join.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityJoinIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  /** The manifest the docs show, applied verbatim — the page cannot drift from the code. */
  static final String MANIFEST = readDocExample();

  static String readDocExample() {
    try {
      return Files.readString(Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent()
          .resolve("docs/apps/demo/entities/30-product-with-category.yaml"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void tables() throws Exception {
    exec("DROP VIEW IF EXISTS connector.\"v_productwithcategory\"",
        "DROP TABLE IF EXISTS connector.t_products_b",
        "DROP TABLE IF EXISTS connector.t_brands");
    rlsTable("t_products", "m_product_id", "m_product_category_id", "name");
    rlsTable("t_product_categories", "m_product_category_id", "name", "description");
  }

  private void rlsTable(String name, String... textColumns) throws Exception {
    EntitySourceTableFixture.create(adminDs, name, "v_productwithcategory", textColumns);
  }

  private void exec(String... sql) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }

  private ValidatableResponse apply(String yaml) {
    return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(yaml).post("/api/entities/apply").then();
  }

  private JsonNode rows(long tenant, long user) throws Exception {
    String body = given().headers(TestIdentity.tenantAndUser(tenant, user))
        .get("/api/entities/productwithcategory").then().statusCode(200).extract().asString();
    return MAPPER.readTree(body);
  }

  private JsonNode rows() throws Exception {
    return rows(1, 1);
  }

  private static JsonNode row(JsonNode rows, String productId) {
    for (JsonNode r : rows) if (productId.equals(r.path("product_id").asText())) return r;
    throw new AssertionError("no row for product " + productId + " in " + rows);
  }

  private static boolean nullish(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull();
  }

  /** The ledger is a jsonb rendered as text in the entity read. */
  private static JsonNode ledger(JsonNode row) throws Exception {
    return MAPPER.readTree(row.path("_conflicts").asText("{}"));
  }

  @Test
  void two_products_of_one_category_stay_two_rows_each_with_the_category() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','Hydraulic pumps')",
        "INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A'), (1,'P2','C1','Pump B')");
    apply(MANIFEST).statusCode(200);

    JsonNode rows = rows();
    assertThat(rows).hasSize(2);
    for (String id : new String[]{"P1", "P2"}) {
      assertThat(row(rows, id).path("category_name").asText()).isEqualTo("Pumps");
      assertThat(row(rows, id).path("category_description").asText()).isEqualTo("Hydraulic pumps");
      assertThat(ledger(row(rows, id))).isEmpty();
    }
    assertThat(row(rows, "P1").path("product_name").asText()).isEqualTo("Pump A");
    assertThat(row(rows, "P2").path("product_name").asText()).isEqualTo("Pump B");
  }

  @Test
  void a_product_without_category_or_with_a_null_key_is_kept_with_null_fields() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','d')",
        "INSERT INTO connector.t_products VALUES (1,'P1',NULL,'No key'), (1,'P2','ZZ','Unknown key')");
    apply(MANIFEST).statusCode(200);

    JsonNode rows = rows();
    assertThat(rows).hasSize(2);
    for (String id : new String[]{"P1", "P2"}) {
      assertThat(nullish(row(rows, id).path("category_name"))).isTrue();
      assertThat(nullish(row(rows, id).path("category_description"))).isTrue();
      assertThat(ledger(row(rows, id))).as("an absent key is not an opinion").isEmpty();
    }
  }

  @Test
  void a_duplicate_key_with_different_values_nulls_the_columns_and_lists_every_row() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Valves','b'), (1,'C1','Pumps','a')",
        "INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A')");
    apply(MANIFEST).statusCode(200);

    JsonNode r = row(rows(), "P1");
    assertThat(nullish(r.path("category_name"))).isTrue();
    assertThat(nullish(r.path("category_description"))).isTrue();
    JsonNode names = ledger(r).path("category_name");
    assertThat(names).hasSize(2);
    assertThat(names.get(0).path("src").asText()).isEqualTo("t_product_categories");
    assertThat(names.get(0).path("value").asText()).isEqualTo("Pumps");
    assertThat(names.get(1).path("value").asText()).isEqualTo("Valves");
    assertThat(ledger(r).path("category_description")).hasSize(2);
  }

  @Test
  void identical_duplicates_are_still_ambiguous() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','a'), (1,'C1','Pumps','a')",
        "INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A')");
    apply(MANIFEST).statusCode(200);

    JsonNode r = row(rows(), "P1");
    assertThat(nullish(r.path("category_name"))).as("identical rows are not trusted either").isTrue();
    JsonNode names = ledger(r).path("category_name");
    assertThat(names).hasSize(2);
    assertThat(names.get(0).path("value").asText()).isEqualTo("Pumps");
    assertThat(names.get(1).path("value").asText()).isEqualTo("Pumps");
  }

  @Test
  void all_null_duplicates_are_ambiguous_with_explicit_nulls() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1',NULL,NULL), (1,'C1',NULL,NULL)",
        "INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A')");
    apply(MANIFEST).statusCode(200);

    JsonNode names = ledger(row(rows(), "P1")).path("category_name");
    assertThat(names).hasSize(2);
    for (JsonNode e : names) {
      assertThat(e.has("value")).as("the null must survive the ledger's null-stripping").isTrue();
      assertThat(e.get("value").isNull()).isTrue();
    }
  }

  @Test
  void the_ledger_bytes_do_not_depend_on_physical_row_order() throws Exception {
    exec("INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A')",
        "INSERT INTO connector.t_product_categories VALUES (1,'C1','X','b'), (1,'C1','X','a')");
    apply(MANIFEST).statusCode(200);
    String first = row(rows(), "P1").path("_conflicts").asText();

    exec("DELETE FROM connector.t_product_categories",
        "INSERT INTO connector.t_product_categories VALUES (1,'C1','X','a'), (1,'C1','X','b')");
    String second = row(rows(), "P1").path("_conflicts").asText();

    assertThat(first).isNotEqualTo("{}");
    assertThat(second).isEqualTo(first);
    JsonNode descriptions = MAPPER.readTree(first).path("category_description");
    assertThat(descriptions.get(0).path("value").asText()).isEqualTo("a");
    assertThat(descriptions.get(1).path("value").asText()).isEqualTo("b");
  }

  /** Gives the two key columns the types a real ingestion gives them (bigint vs numeric(18,4)), before any row. */
  private void typedKeys(String productKeyType, String lookupKeyType) throws Exception {
    exec("ALTER TABLE connector.t_products ALTER COLUMN m_product_category_id TYPE " + productKeyType
            + " USING m_product_category_id::" + productKeyType,
        "ALTER TABLE connector.t_product_categories ALTER COLUMN m_product_category_id TYPE " + lookupKeyType
            + " USING m_product_category_id::" + lookupKeyType);
  }

  @Test
  void a_bigint_key_joins_a_fixed_scale_numeric_key_of_the_same_value() throws Exception {      // #864 headline
    typedKeys("bigint", "numeric(18,4)");
    exec("INSERT INTO connector.t_product_categories VALUES (1, 1000021, 'Pompes', 'd')",
        "INSERT INTO connector.t_products VALUES (1,'P1',1000021,'Pump A')");
    apply(MANIFEST).statusCode(200);
    assertThat(row(rows(), "P1").path("category_name").asText()).isEqualTo("Pompes");
  }

  @Test
  void the_same_value_at_different_scales_joins_and_a_different_fraction_does_not() throws Exception {
    typedKeys("numeric(18,4)", "numeric(18,2)");
    exec("INSERT INTO connector.t_product_categories VALUES (1, 1000021.50, 'Half', 'd')",
        "INSERT INTO connector.t_products VALUES (1,'P1',1000021.5000,'same value'), (1,'P2',1000021.5001,'other fraction')");
    apply(MANIFEST).statusCode(200);
    JsonNode rows = rows();
    assertThat(row(rows, "P1").path("category_name").asText()).isEqualTo("Half");
    assertThat(nullish(row(rows, "P2").path("category_name"))).as("no rounding: …5001 is not …5").isTrue();
  }

  @Test
  void a_key_that_is_text_shaped_keeps_its_text_comparison() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'7','Seven','d'), (1,'01','ZeroOne','d')",
        "INSERT INTO connector.t_products VALUES (1,'P1','007','leading zeros'), (1,'P2','1','one'), (1,'P3','7','seven')");
    apply(MANIFEST).statusCode(200);
    JsonNode rows = rows();
    assertThat(nullish(row(rows, "P1").path("category_name"))).as("007 is not 7").isTrue();
    assertThat(nullish(row(rows, "P2").path("category_name"))).as("1 is not 01").isTrue();
    assertThat(row(rows, "P3").path("category_name").asText()).isEqualTo("Seven");
  }

  @Test
  void two_text_identifiers_that_are_the_same_decimal_number_are_one_key_by_decision() throws Exception {
    // Accepted, documented semantics (#864): the rule is by shape, not by column type. A text column holding
    // '1.0' joins a text lookup key '1'. If this ever needs to change, it is a new design (type-aware), not a patch.
    exec("INSERT INTO connector.t_product_categories VALUES (1,'1','One','d')",
        "INSERT INTO connector.t_products VALUES (1,'P1','1.0','dotted one'), (1,'P2','1.5','not one')");
    apply(MANIFEST).statusCode(200);
    JsonNode rows = rows();
    assertThat(row(rows, "P1").path("category_name").asText()).isEqualTo("One");
    assertThat(nullish(row(rows, "P2").path("category_name"))).isTrue();
  }

  @Test
  void a_long_digit_string_and_a_non_numeric_string_keep_their_text_match_and_never_break_the_view() throws Exception {
    String forty = "1".repeat(40);
    exec("INSERT INTO connector.t_product_categories VALUES (1,'" + forty + "','Long','d'), (1,'not-a-number','Text','d')",
        "INSERT INTO connector.t_products VALUES (1,'P1','" + forty + "','long'), (1,'P2','not-a-number','text')");
    apply(MANIFEST).statusCode(200);
    JsonNode rows = rows();                                       // 200: no cast error at read time
    assertThat(row(rows, "P1").path("category_name").asText()).isEqualTo("Long");
    assertThat(row(rows, "P2").path("category_name").asText()).isEqualTo("Text");
  }

  @Test
  void lookup_rows_that_differ_only_by_scale_are_one_ambiguous_key() throws Exception {
    typedKeys("bigint", "numeric");   // unconstrained: 5 and 5.0000 keep their own scale, so only the rule can group them
    exec("INSERT INTO connector.t_product_categories VALUES (1, 5, 'A', 'x'), (1, 5.0000, 'B', 'y')",
        "INSERT INTO connector.t_products VALUES (1,'P1',5,'Pump A')");
    apply(MANIFEST).statusCode(200);
    JsonNode r = row(rows(), "P1");
    assertThat(nullish(r.path("category_name"))).as("1 key, 2 rows: ambiguous, never a pick").isTrue();
    assertThat(ledger(r).path("category_name")).hasSize(2);
  }

  @Test
  void a_null_key_is_still_kept_with_null_fields() throws Exception {
    typedKeys("bigint", "numeric(18,4)");
    exec("INSERT INTO connector.t_product_categories VALUES (1, 1, 'A', 'x')",
        "INSERT INTO connector.t_products VALUES (1,'P1',NULL,'no key')");
    apply(MANIFEST).statusCode(200);
    JsonNode r = row(rows(), "P1");
    assertThat(nullish(r.path("category_name"))).isTrue();
    assertThat(ledger(r)).as("an absence is not an opinion").isEmpty();
  }

  @Test
  void the_same_lookup_key_in_two_tenants_never_crosses() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','T1Cat','d1'), (2,'C1','T2Cat','d2')",
        "INSERT INTO connector.t_products VALUES (1,'P1','C1','One'), (2,'P2','C1','Two')");
    apply(MANIFEST).statusCode(200);

    JsonNode t1 = rows(1, 1);
    assertThat(t1).hasSize(1);
    assertThat(row(t1, "P1").path("category_name").asText()).isEqualTo("T1Cat");
    assertThat(ledger(row(t1, "P1"))).isEmpty();

    JsonNode t2 = rows(2, 2);
    assertThat(t2).hasSize(1);
    assertThat(row(t2, "P2").path("category_name").asText()).isEqualTo("T2Cat");
    assertThat(ledger(row(t2, "P2"))).as("two tenants' C1 are not one ambiguous key").isEmpty();
  }

  @Test
  void a_key_that_differs_by_case_or_leading_zero_does_not_match() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','d'), (1,'1','One','d')",
        "INSERT INTO connector.t_products VALUES (1,'P1','c1','lower'), (1,'P2','01','zero')");
    apply(MANIFEST).statusCode(200);

    JsonNode rows = rows();
    assertThat(rows).hasSize(2);
    assertThat(nullish(row(rows, "P1").path("category_name"))).as("text = text, no case folding").isTrue();
    assertThat(nullish(row(rows, "P2").path("category_name"))).as("text = text, no numeric reading").isTrue();
  }

  @Test
  void from_is_the_fusion_winner_when_the_fused_column_is_in_conflict() throws Exception {
    rlsTable("t_products_b", "m_product_id", "m_product_category_id", "name");
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','d1'), (1,'C2','Valves','d2')",
        "INSERT INTO connector.t_products VALUES (1,'P1','C1','A')",
        "INSERT INTO connector.t_products_b VALUES (1,'P1','C2','A')");
    String twoSources = MANIFEST.replace("joins:",
        "  - table: t_products_b\n    map:\n      m_product_id: product_id\n"
            + "      m_product_category_id: category_id\njoins:");
    apply(twoSources).statusCode(200);

    JsonNode r = row(rows(), "P1");
    assertThat(r.path("category_name").asText()).as("the first source wins category_id, the join follows it").isEqualTo("Pumps");
    JsonNode ledger = ledger(r);
    assertThat(ledger.path("category_id")).as("the fusion conflict stays on the ledger").hasSize(2);
    assertThat(ledger.has("category_name")).as("the join adds nothing to it").isFalse();
  }

  @Test
  void re_applying_without_joins_drops_the_joined_columns() throws Exception {
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','d')",
        "INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A')");
    apply(MANIFEST).statusCode(200);
    assertThat(row(rows(), "P1").has("category_name")).isTrue();

    apply(MANIFEST.substring(0, MANIFEST.indexOf("joins:"))).statusCode(200);
    assertThat(row(rows(), "P1").has("category_name")).as("nothing stale stays readable").isFalse();
  }

  @Test
  void a_lookup_column_that_does_not_exist_is_a_400_naming_the_lookup_not_the_source() throws Exception {
    exec("INSERT INTO connector.t_products VALUES (1,'P1','C1','Pump A')");
    // The lookup has no "nme"; the SOURCE has a column called "name" — the blame must not wander.
    apply(MANIFEST.replace("      name: category_name", "      nme: category_name"))
        .statusCode(400)
        .body("error", containsString("t_product_categories"))
        .body("error", containsString("nme"))
        .body("error", not(containsString("t_products")));

    exec("ALTER TABLE connector.t_product_categories DROP COLUMN description");
    apply(MANIFEST)
        .statusCode(400)
        .body("error", containsString("t_product_categories"))
        .body("error", containsString("description"));
  }

  @Test
  void a_missing_lookup_table_is_the_409_of_a_missing_source() throws Exception {
    exec("DROP TABLE connector.t_product_categories");
    apply(MANIFEST).statusCode(409).body("error", containsString("t_product_categories"));
  }

  @Test
  void two_lookups_each_keep_their_own_contract_and_only_the_ambiguous_one_reaches_the_ledger() throws Exception {
    exec("ALTER TABLE connector.t_products ADD COLUMN brand_id text");
    rlsTable("t_brands", "id", "label");
    exec("INSERT INTO connector.t_product_categories VALUES (1,'C1','Pumps','d')",
        "INSERT INTO connector.t_brands VALUES (1,'B1','Acme'), (1,'B1','ACME'), (1,'B2','Solid')",
        "INSERT INTO connector.t_products (tenant_id, m_product_id, m_product_category_id, name, brand_id) VALUES "
            + "(1,'P1','C1','Pump A','B1'), (1,'P2','C1','Pump B','B2'), (1,'P3','C1','Pump C','B9')");
    apply("""
        kind: Entity
        name: ProductWithCategory
        match: [product_id]
        scopes: [catalogue]
        sources:
          - table: t_products
            map: { m_product_id: product_id, m_product_category_id: category_id, brand_id: brand_id }
        joins:
          - table: t_product_categories
            from: category_id
            to: m_product_category_id
            map: { name: category_name }
          - table: t_brands
            from: brand_id
            to: id
            map: { label: brand_label }
        """).statusCode(200);

    JsonNode rows = rows();
    assertThat(rows).hasSize(3);

    JsonNode p1 = row(rows, "P1");
    assertThat(p1.path("category_name").asText()).as("the clean lookup still joins").isEqualTo("Pumps");
    assertThat(nullish(p1.path("brand_label"))).as("the ambiguous lookup abstains").isTrue();
    assertThat(ledger(p1).has("category_name")).as("the clean lookup writes nothing").isFalse();
    assertThat(ledger(p1).path("brand_label")).hasSize(2);
    assertThat(ledger(p1).path("brand_label").get(0).path("src").asText()).isEqualTo("t_brands");

    JsonNode p2 = row(rows, "P2");
    assertThat(p2.path("category_name").asText()).isEqualTo("Pumps");
    assertThat(p2.path("brand_label").asText()).isEqualTo("Solid");
    assertThat(ledger(p2)).isEmpty();

    JsonNode p3 = row(rows, "P3");
    assertThat(p3.path("category_name").asText()).isEqualTo("Pumps");
    assertThat(nullish(p3.path("brand_label"))).as("an unknown brand is an absence, not an opinion").isTrue();
    assertThat(ledger(p3)).isEmpty();
  }
}
