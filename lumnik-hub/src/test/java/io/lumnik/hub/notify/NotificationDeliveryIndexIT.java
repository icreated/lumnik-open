package io.lumnik.hub.notify;

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
 * #1902: the mail deliveries page (GET /api/platform/notification-deliveries, {@code lm notify deliveries}) reads a
 * history that grows by one row per event and rule through an index that already holds the page's order, instead of
 * reading the tenant's whole history and sorting it. The twin of {@code WebhookDeliveryIndexesIT} (#902): same
 * method — the SQL of {@code NotificationDeliveries.list} as pgjdbc sends it, planned as {@code lumnik_app} under RLS
 * with {@code enable_sort = off}, so the test proves the ordered path EXISTS on a test-sized table.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class NotificationDeliveryIndexIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject AgroalDataSource appDs;   // default DS = lumnik_app (RLS enforced)

  @ParameterizedTest
  @ValueSource(strings = {"force_custom_plan", "force_generic_plan"})
  void the_page_reads_the_tenants_deliveries_in_its_order_without_sorting_them(String planCacheMode) throws Exception {
    List<JsonNode> plan = planAsApp(planCacheMode,
        "SELECT id, rule_id, event_id, recipient, event_type, attempts, succeeded, error,"
            + " COALESCE(attempted_at, created_at) FROM core.notification_delivery WHERE tenant_id = $1"
            + " ORDER BY CASE WHEN succeeded THEN 2 WHEN attempts >= 3 THEN 0 ELSE 1 END,"
            + " COALESCE(attempted_at, created_at) DESC, id DESC LIMIT $2",
        "1, 500");

    assertThat(plan).as("a sort means the tenant's whole history was read first: %s", plan.get(0))
        .noneMatch(n -> n.path("Node Type").asText().contains("Sort"));
    assertThat(plan).as("the page is read in order from the tenant's page index: %s", plan.get(0))
        .anyMatch(n -> "idx_notification_delivery_page".equals(n.path("Index Name").asText())
            && n.path("Index Cond").asText().contains("tenant_id"));
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
