package io.lumnik.hub.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #902: the two delivery reads that page a never-purged history reach their rows through an index
 * that already holds them in the page's order, instead of reading the history and sorting or
 * filtering it.
 *
 * <p>The SQL is what Hibernate sends for each repository method ({@code log.sql}), its {@code ?}
 * numbered, executed with the limit at the repository's 500 cap — under a custom plan (what the
 * first executions get) and a generic one (what pgjdbc's server-prepared statement may settle on).
 * The plans are read as {@code lumnik_app} under RLS, the production role, like
 * {@code RunLedgerIndexesIT}. {@code enable_sort = off} takes away every path but an ordered index
 * scan, so these tests prove the path EXISTS — on a test-sized table the planner may rightly prefer a
 * scan and a sort; on the history these pages read, it is the only sane one.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WebhookDeliveryIndexesIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject AgroalDataSource appDs;   // default DS = lumnik_app (RLS enforced)

  @ParameterizedTest
  @ValueSource(strings = {"force_custom_plan", "force_generic_plan"})
  void the_outputs_page_reads_the_tenants_deliveries_failures_first_without_sorting_them(String planCacheMode)
      throws Exception {
    // WebhookDeliveryRepository.recentForTenant (GET /api/platform/deliveries).
    List<JsonNode> plan = planAsApp(planCacheMode,
        "select * from core.webhook_delivery wd1_0 where wd1_0.tenant_id=$1"
            + " order by wd1_0.succeeded, wd1_0.attempted_at desc offset $2 rows fetch first $3 rows only",
        "1, 0, 500");

    assertThat(plan).as("a sort means the tenant's whole history was read first: %s", plan.get(0))
        .noneMatch(n -> n.path("Node Type").asText().contains("Sort"));
    assertThat(plan).as("the page is read in order from the tenant's outcome index: %s", plan.get(0))
        .anyMatch(n -> "idx_webhook_delivery_tenant_outcome".equals(n.path("Index Name").asText())
            && n.path("Index Cond").asText().contains("tenant_id"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"force_custom_plan", "force_generic_plan"})
  void an_endpoints_failed_view_reads_only_its_failures(String planCacheMode) throws Exception {
    // WebhookDeliveryRepository.recentFailedForEndpoint (GET .../integrations/{id}/deliveries?status=failed).
    List<JsonNode> plan = planAsApp(planCacheMode,
        "select * from core.webhook_delivery wd1_0 where wd1_0.endpoint_id=$1 and wd1_0.succeeded=false"
            + " order by wd1_0.attempted_at desc offset $2 rows fetch first $3 rows only",
        "1, 0, 500");

    assertThat(plan).as("a sort means the endpoint's failures were gathered before the page: %s", plan.get(0))
        .noneMatch(n -> n.path("Node Type").asText().contains("Sort"));
    assertThat(plan).as("the failures are read from an index that holds nothing else: %s", plan.get(0))
        .anyMatch(n -> "idx_webhook_delivery_endpoint_failed".equals(n.path("Index Name").asText())
            && n.path("Index Cond").asText().contains("endpoint_id"));
  }

  /**
   * Every node of the plan {@code sql} gets when executed with {@code args}, prepared and planned as
   * lumnik_app for tenant 1 under {@code planCacheMode}.
   */
  private List<JsonNode> planAsApp(String planCacheMode, String sql, String args) throws Exception {
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      boolean prepared = false;
      try {
        st.execute("SET LOCAL app.current_tenant = '1'");
        st.execute("SET LOCAL enable_sort = off");
        st.execute("SET LOCAL plan_cache_mode = " + planCacheMode);
        st.execute("PREPARE page AS " + sql);
        prepared = true;
        try (ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) EXECUTE page(" + args + ")")) {
          rs.next();
          List<JsonNode> nodes = new ArrayList<>();
          collect(MAPPER.readTree(rs.getString(1)).get(0).path("Plan"), nodes);
          return nodes;
        }
      } finally {
        c.rollback();
        c.setAutoCommit(true);
        // PREPARE is not transactional: the rollback leaves it on this pooled connection. Only this
        // statement goes — DEALLOCATE ALL would also drop pgjdbc's own server-prepared statements.
        if (prepared) st.execute("DEALLOCATE page");
      }
    }
  }

  private static void collect(JsonNode node, List<JsonNode> out) {
    out.add(node);
    for (JsonNode child : node.path("Plans")) collect(child, out);
  }
}
