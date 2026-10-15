package io.lumnik.hub.connector.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #892: the runs ledger ({@code GET /api/platform/runs}) and the deletion of a connector's run
 * history reach their rows through an index instead of scanning tables that are never purged.
 *
 * <p>The plans are read as {@code lumnik_app} under RLS, the production role: a predicate whose
 * operator is not LEAKPROOF cannot be an index condition behind a policy, so a plan read as the
 * table owner can show an index the hub never gets (#758). Each plan switch below takes away
 * every path but the indexed one, so these tests prove the path EXISTS — on a small table the
 * planner may rightly prefer a scan; on the history this ledger reads, it is the only sane one.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class RunLedgerIndexesIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject AgroalDataSource appDs;   // default DS = lumnik_app (RLS enforced)

  @Test
  void the_ledger_page_walks_the_tenants_newest_runs_without_sorting_their_history() throws Exception {
    // The SQL Hibernate renders for ConnectorRunRepository.findRecent with no filter. The tenant
    // predicate is the query's own (RunAdminResourceTest.list_shows_no_other_tenants_run): RLS's
    // policy is an OR on a setting, which no index can serve.
    List<JsonNode> plan = planAsApp(
        "SELECT * FROM connector.connector_run WHERE tenant_id = 1"
            + " ORDER BY started_at DESC OFFSET 0 ROWS FETCH FIRST 200 ROWS ONLY",
        "enable_sort = off");

    assertThat(plan).as("a sort means the whole history was read first: %s", plan.get(0))
        .noneMatch(n -> n.path("Node Type").asText().contains("Sort"));
    assertThat(plan).as("the page is read through an index on the tenant: %s", plan.get(0))
        .anyMatch(n -> "connector_run".equals(n.path("Relation Name").asText())
            && n.path("Index Cond").asText().contains("tenant_id"));
  }

  @Test
  void the_pages_dead_letter_counts_reach_their_rows_by_run_id() throws Exception {
    // The SQL Hibernate renders for ConnectorDeadLetterRepository.countByRuns: no tenant predicate,
    // the run ids come from the tenant's own page and RLS is the floor.
    String benign = SkipReason.BENIGN_NAMES.stream().map(n -> "'" + n + "'").collect(Collectors.joining(","));
    List<JsonNode> plan = planAsApp(
        "SELECT run_id, count(id) FROM connector.connector_dead_letter"
            + " WHERE run_id IN ('" + UUID.randomUUID() + "','" + UUID.randomUUID() + "')"
            + " AND deleted_at IS NULL AND skip_reason NOT IN (" + benign + ")"
            + " GROUP BY run_id",
        "enable_seqscan = off");

    assertThat(plan).as("the dead letters are found by run, not by scanning them all: %s", plan.get(0))
        .anyMatch(n -> n.path("Index Cond").asText().startsWith("(run_id "));
  }

  /**
   * Deleting a connector cascades to its runs (V9), and every deleted run fires the referential
   * action of each foreign key that names it — one lookup per run per key, in the delete's one
   * transaction. Unindexed, each lookup is a scan of the referencing table: R runs × the whole
   * dead-letter table, twice. A partial index on {@code col IS NOT NULL} serves that lookup too
   * (an equality on the column implies it). Its predicate is compared as {@code pg_get_expr}
   * renders it without the pretty flag — always parenthesised, the column quoted as
   * {@code quote_ident} quotes it — whatever parentheses the migration wrote.
   */
  @Test
  void every_foreign_key_into_the_run_history_is_indexed() throws Exception {
    String fkColumns = """
        SELECT con.conrelid::regclass || '.' || a.attname
        FROM pg_constraint con
        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
        WHERE con.contype = 'f' AND con.confrelid = 'connector.connector_run'::regclass
        """;
    String unindexed = """
         AND NOT EXISTS (SELECT 1 FROM pg_index i
                         WHERE i.indrelid = con.conrelid AND i.indkey[0] = con.conkey[1]
                           AND (i.indpred IS NULL
                                OR pg_get_expr(i.indpred, i.indrelid) = '(' || quote_ident(a.attname) || ' IS NOT NULL)'))
        """;

    // Anchored on the catalog, so a key added later is checked without anyone listing it here —
    // but never vacuously: the three keys known today must be found.
    assertThat(column(fkColumns)).contains("connector.connector_dead_letter.run_id",
        "connector.connector_dead_letter.resolved_run_id", "core.resolution_run_coverage.run_id");
    assertThat(column(fkColumns + unindexed)).as("foreign keys into connector_run with no index").isEmpty();
  }

  /** Every node of {@code EXPLAIN (FORMAT JSON) sql}, planned as lumnik_app for tenant 1. */
  private List<JsonNode> planAsApp(String sql, String planSwitch) throws Exception {
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      try {
        st.execute("SET LOCAL app.current_tenant = '1'");
        st.execute("SET LOCAL " + planSwitch);
        try (ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
          rs.next();
          List<JsonNode> nodes = new ArrayList<>();
          collect(MAPPER.readTree(rs.getString(1)).get(0).path("Plan"), nodes);
          return nodes;
        }
      } finally {
        c.rollback();
      }
    }
  }

  private static void collect(JsonNode node, List<JsonNode> out) {
    out.add(node);
    for (JsonNode child : node.path("Plans")) collect(child, out);
  }

  private List<String> column(String sql) throws Exception {
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
      List<String> out = new ArrayList<>();
      while (rs.next()) out.add(rs.getString(1));
      return out;
    }
  }
}
