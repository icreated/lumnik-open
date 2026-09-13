package io.lumnik.hub.audit;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit trail is append-only for the request-path role — at the database, not by convention.
 *
 * <p>Under the shared DevServices container every datasource is the table owner, so a grant is
 * invisible there; only the RLS-enforced harness connects as the real {@code lumnik_app} and can
 * see what it is allowed to do. The write path (outbox → dispatcher → event, on the admin
 * datasource) is asserted first, so a refusal below cannot be the harness failing to reach the
 * table at all.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class AuditEventAppendOnlyRlsIT {

  private static final long TENANT = 4343L;

  @Inject AgroalDataSource appDs;                        // lumnik_app
  @Inject @DataSource("admin") AgroalDataSource adminDs; // lumnik_admin
  @Inject AuditDispatcher dispatcher;

  private long countAsAdmin(String key) throws SQLException {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "SELECT count(*) FROM core.audit_event WHERE tenant_id = ? AND entity_key = ?")) {
      ps.setLong(1, TENANT);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String landOneRow() throws SQLException {
    String key = "append-only-" + System.nanoTime();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO core.audit_outbox "
           + "(tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at) "
           + "VALUES (?, 1, 'Probe', ?, 'CREATE', '{}'::jsonb, now())")) {
      ps.setLong(1, TENANT);
      ps.setString(2, key);
      ps.executeUpdate();
    }
    dispatcher.drain();
    assertThat(countAsAdmin(key)).as("the dispatcher still lands the row on the admin datasource")
        .isEqualTo(1L);
    return key;
  }

  private void asApp(String sql) throws SQLException {
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("BEGIN");
      st.execute("SET LOCAL app.current_tenant = '" + TENANT + "'");
      try {
        st.execute(sql);
      } finally {
        st.execute("ROLLBACK");
      }
    }
  }

  @Test
  void the_app_role_cannot_update_or_delete_an_audit_row() throws Exception {
    String key = landOneRow();

    assertThatThrownBy(() -> asApp(
        "UPDATE core.audit_event SET operation = 'DELETE' WHERE entity_key = '" + key + "'"))
        .as("UPDATE on the trail is refused by the grant, not left to convention")
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> asApp(
        "DELETE FROM core.audit_event WHERE entity_key = '" + key + "'"))
        .as("DELETE on the trail is refused by the grant, not left to convention")
        .hasMessageContaining("permission denied");
    assertThatThrownBy(() -> asApp(
        "DELETE FROM core.audit_event_default WHERE entity_key = '" + key + "'"))
        .as("the partition refuses a direct DELETE too")
        .hasMessageContaining("permission denied");

    assertThat(countAsAdmin(key)).isEqualTo(1L);
  }

  @Test
  void the_app_role_still_reads_its_tenant_and_the_admin_role_still_purges() throws Exception {
    String key = landOneRow();

    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("BEGIN");
      st.execute("SET LOCAL app.current_tenant = '" + TENANT + "'");
      try (ResultSet rs = st.executeQuery(
          "SELECT count(*) FROM core.audit_event WHERE entity_key = '" + key + "'")) {
        rs.next();
        assertThat(rs.getLong(1)).as("reading the trail inside the tenant stays allowed").isEqualTo(1L);
      }
      st.execute("COMMIT");
    }

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      int deleted = st.executeUpdate(
          "DELETE FROM core.audit_event WHERE entity_key = '" + key + "'");
      assertThat(deleted).as("the retention purge's role keeps its DELETE").isEqualTo(1);
    }
  }
}
