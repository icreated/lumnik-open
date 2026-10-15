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
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #871: a filter on a non-match canonical column of a joined entity (every one of them wrapped in
 * an {@code array_agg(...)[1]} Postgres cannot estimate the selectivity of) makes the planner guess
 * the lookup join will run once — a guess that is right or wrong independently of data volume. When
 * it is wrong and the planner picks a Nested Loop anyway, an INLINED lookup CTE pays for its own
 * aggregate pipeline once per outer row instead of once, total. {@code enable_hashjoin}/
 * {@code enable_mergejoin} are forced off here so the test does not depend on which plan the
 * optimizer would have chosen on its own that day — it proves the invariant {@link
 * EntityViewCompiler#lookupCte} now guarantees (a {@code MATERIALIZED} lookup CTE) even in the worst
 * case the planner can be pushed into.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityJoinLookupMaterializedIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

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
    // create() already drops the view and its own table, in one lock_timeout-protected transaction
    // (EntitySourceTableFixture's own javadoc, #755/#761) — a separate, unprotected DROP here would
    // be both redundant and a reintroduction of the stall risk that recipe exists to avoid.
    EntitySourceTableFixture.create(adminDs, "t_products", "v_productwithcategory",
        "m_product_id", "m_product_category_id", "name");
    EntitySourceTableFixture.create(adminDs, "t_product_categories", "v_productwithcategory",
        "m_product_category_id", "name", "description");

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      // 50 categories so the lookup's own aggregate is not a one-row no-op.
      for (int cat = 1; cat <= 50; cat++) {
        st.execute("INSERT INTO connector.t_product_categories VALUES (1,'C" + cat
            + "','Category " + cat + "','d" + cat + "')");
      }
      // 200 products, half named 'Widget' — a non-match canonical column (product_name), spread
      // across every category so the lookup is actually exercised once per outer row.
      for (int p = 1; p <= 200; p++) {
        String name = p % 2 == 0 ? "Widget" : ("Other " + p);
        int cat = 1 + (p % 50);
        st.execute("INSERT INTO connector.t_products VALUES (1,'P" + p + "','C" + cat + "','" + name + "')");
      }
      c.commit();
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST).post("/api/entities/apply").then().statusCode(200);
  }

  /**
   * Every aggregate node anywhere in the plan, depth-first. {@code EXPLAIN (FORMAT JSON)} names
   * both a {@code GroupAggregate} and a {@code HashAggregate} {@code "Aggregate"} (the
   * {@code Strategy} field, not the node type, says which) — the {@code FORMAT TEXT} labels are a
   * rendering choice, not the wire shape.
   */
  private static void collectAggregates(JsonNode plan, List<JsonNode> out) {
    if ("Aggregate".equals(plan.path("Node Type").asText())) out.add(plan);
    for (JsonNode child : plan.path("Plans")) collectAggregates(child, out);
  }

  /**
   * The real (non-{@code EXPLAIN}) row count a filter matches. Checked BEFORE the plan, and exactly
   * — not "at least" — so a filter that (by a typo, or an unrelated change) silently stops matching
   * anything cannot pass this test vacuously. The earlier version inferred "the filter matched
   * something" from the max {@code Actual Rows} across every aggregate in the plan, including the
   * lookup's own — which always produces ~50 rows (one per category) regardless of whether the
   * OUTER filter matched any product at all, so a 0-row match would have passed undetected (review,
   * PR #872).
   */
  private long countMatches(String whereClause) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT count(*) FROM connector.\"v_productwithcategory\" " + whereClause)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * Runs {@code EXPLAIN (ANALYZE, FORMAT JSON)} for the exact shape {@code EntityReadResource}
   * builds for the given filter(s), with the join strategies that could hide the #871 landmine
   * disabled, and asserts no aggregate anywhere in the plan is re-executed per outer row. Before the
   * fix (a plain, inlined {@code lk0}), the lookup's own aggregate's {@code Actual Loops} equals the
   * number of surviving fused rows; after {@code MATERIALIZED}, every aggregate runs exactly once.
   */
  private void assertNoAggregateReexecutesPerOuterRow(String whereClause, long expectedMatchedRows) throws Exception {
    assertThat(countMatches(whereClause))
        .as("the filter '%s' must match exactly %s products", whereClause, expectedMatchedRows)
        .isEqualTo(expectedMatchedRows);

    String json;
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      // The worst case the planner could be pushed into, not the one it happens to pick today.
      st.execute("SET LOCAL enable_hashjoin = off");
      st.execute("SET LOCAL enable_mergejoin = off");
      try (ResultSet rs = st.executeQuery(
          "EXPLAIN (ANALYZE, FORMAT JSON) "
          + "SELECT \"product_id\",\"category_id\",\"product_name\",\"category_name\",\"category_description\","
          + "\"_conflicts\"::text FROM connector.\"v_productwithcategory\" "
          + whereClause
          + "ORDER BY \"product_id\",\"category_id\",\"product_name\",\"category_name\",\"category_description\" "
          + "LIMIT 100")) {
        rs.next();
        json = rs.getString(1);
      }
      c.rollback();
    }

    JsonNode plan = MAPPER.readTree(json).get(0).path("Plan");
    List<JsonNode> aggregates = new ArrayList<>();
    collectAggregates(plan, aggregates);
    assertThat(aggregates).as("expected at least the fused branch's own aggregate").isNotEmpty();

    for (JsonNode agg : aggregates) {
      assertThat(agg.path("Actual Loops").asLong())
          .as("aggregate re-executed per outer row (the #871 landmine): %s", agg)
          .isEqualTo(1);
    }
  }

  @Test
  void a_filter_on_a_non_match_column_does_not_recompute_the_lookup_per_outer_row() throws Exception {
    // The exact shape EntityReadResource.selectSql builds for ?product_name=Widget. 200 products,
    // half named 'Widget' (p % 2 == 0 in the seed above) — exactly 100 matches.
    assertNoAggregateReexecutesPerOuterRow("WHERE \"product_name\"::text = 'Widget' ", 100);
  }

  @Test
  void a_filter_combining_the_match_key_and_a_non_match_column_is_also_safe() throws Exception {
    // #871's own "to establish" list named this combination explicitly: a match-key equality
    // (pushed into the UNION ALL branches, narrowing the fused side to one row) alongside a
    // non-match filter (which alone cannot be pushed past the aggregation). The match-key predicate
    // dominates here — the fused side's true cardinality and its ~1-row estimate agree for once —
    // but #871's fix must hold regardless of why the outer side ends up small. P2 is even, so it is
    // named 'Widget' in the seed above — exactly 1 match.
    assertNoAggregateReexecutesPerOuterRow(
        "WHERE \"product_id\"::text = 'P2' AND \"product_name\"::text = 'Widget' ", 1);
  }
}
