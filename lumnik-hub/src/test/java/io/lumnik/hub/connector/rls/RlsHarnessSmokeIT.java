package io.lumnik.hub.connector.rls;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the harness delivers what it promises: app = non-owner, RLS genuinely enforced. */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class RlsHarnessSmokeIT {

  @Inject AgroalDataSource appDs;                        // default = lumnik_app
  @Inject @DataSource("admin") AgroalDataSource adminDs; // lumnik_admin (owner)

  private String scalarText(AgroalDataSource ds, String sql) {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  @Test
  void datasources_connect_as_the_prod_roles() {
    assertThat(scalarText(appDs, "SELECT current_user")).isEqualTo("lumnik_app");
    assertThat(scalarText(adminDs, "SELECT current_user")).isEqualTo("lumnik_admin");
  }

  @Test
  void rls_is_enforced_for_the_app_role() throws Exception {
    // Seed a probe row as the OWNER (owners bypass RLS), tenant 4242.
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.connector (tenant_id, name, type_id) "
          + "VALUES (4242, 'rls-probe', 'csv-file') ON CONFLICT DO NOTHING");
    }
    // As the app role with NO tenant GUC: the probe row must be INVISIBLE.
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT count(*) FROM connector.connector WHERE name = 'rls-probe'")) {
      rs.next();
      assertThat(rs.getLong(1)).as("non-owner without tenant GUC must see nothing").isEqualTo(0L);
    }
    // With the right tenant GUC: visible.
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("BEGIN");
      st.execute("SET LOCAL app.current_tenant = '4242'");
      try (ResultSet rs = st.executeQuery(
          "SELECT count(*) FROM connector.connector WHERE name = 'rls-probe'")) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1L);
      }
      st.execute("COMMIT");
    }
  }
}
