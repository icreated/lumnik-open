package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.testsupport.LogProbe;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #620 — the end-of-run trigger (design doc §7's first of two): {@link EntityResolutionRunHook}
 * observes {@link ConnectorRunCompleted} and drives {@link EntityResolutionPass#runIncremental}.
 * Calls the package-private observer method directly rather than firing the CDI event and
 * polling — {@code @ObservesAsync} delivery timing is not what this test exists to prove;
 * {@link EntityResolutionPassIT} already covers {@code runIncremental} itself in depth.
 */
@QuarkusTest
class EntityResolutionRunHookTest {

  @Inject EntityResolutionRunHook hook;
  @Inject EntityRegistry registry;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY_TYPE = "resit620hook";
  private static final String TABLE = "t_res_it620_hook_erp";

  private static final String MANIFEST = """
      kind: Entity
      name: ResIt620Hook
      sources:
        - table: t_res_it620_hook_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_it620_hook_erp: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
      """;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector." + TABLE);
      st.execute("CREATE TABLE connector." + TABLE + " ("
          + "id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, _run_id uuid, "
          + "\"CUSTOMER_ID\" text, \"EMAIL_COL\" text)");
      st.execute("DELETE FROM core.entity_identity WHERE entity_type = '" + ENTITY_TYPE + "'");
    }
    EntityManifest m = new EntityManifestParser().parse(MANIFEST);
    registry.put(m);
  }

  @Test
  void a_completed_run_event_drives_the_incremental_pass_over_its_own_rows() throws Exception {
    long tenant = 98_001;
    UUID runId = UUID.randomUUID();
    UUID connectorId = UUID.randomUUID();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector." + TABLE + " (tenant_id, _run_id, \"CUSTOMER_ID\", \"EMAIL_COL\") "
                 + "VALUES (?, ?, 'hook-1', 'hook1@example.com')")) {
      ps.setLong(1, tenant);
      ps.setObject(2, runId);
      ps.executeUpdate();
    }

    hook.onRunCompleted(new ConnectorRunCompleted(connectorId, tenant, runId, Instant.now()));

    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT state FROM core.entity_identity "
                 + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = 'hook-1'")) {
      ps.setLong(1, tenant);
      ps.setString(2, ENTITY_TYPE);
      ps.setString(3, TABLE);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("the hook must have driven a crosswalk decision").isTrue();
        assertThat(rs.getString(1)).isEqualTo("resolved");
      }
    }
  }

  /**
   * #622's observability gap: {@code onRunCompleted} used to discard the {@code PassSummary}
   * {@code runIncremental} returns — the automatic, most-common trigger left no trace of what it
   * decided, unlike a manual replay (whose summary the REST door returns). Logging now lives in
   * {@link EntityResolutionPass} itself (icreated's review on PR #645 — centralized so every
   * trigger, not just this one, gets it), so the probe watches that logger.
   */
  @Test
  void a_completed_run_event_logs_the_pass_summary() throws Exception {
    long tenant = 98_002;
    UUID runId = UUID.randomUUID();
    UUID connectorId = UUID.randomUUID();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector." + TABLE + " (tenant_id, _run_id, \"CUSTOMER_ID\", \"EMAIL_COL\") "
                 + "VALUES (?, ?, 'hook-2', 'hook2@example.com')")) {
      ps.setLong(1, tenant);
      ps.setObject(2, runId);
      ps.executeUpdate();
    }

    List<LogProbe.Line> lines = LogProbe.capture(EntityResolutionPass.class, () ->
        hook.onRunCompleted(new ConnectorRunCompleted(connectorId, tenant, runId, Instant.now())));

    assertThat(lines)
        .as("the pass summary must be logged, not merely computed and discarded")
        .anySatisfy(line -> {
          assertThat(line.level().intValue()).isGreaterThanOrEqualTo(Level.INFO.intValue());
          assertThat(line.message())
              .contains("rows_seen=1").contains("resolved=1").contains(runId.toString());
        });
  }
}
