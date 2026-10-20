package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1198: a {@code t_*} table holds every tenant's rows, so a row is unique per tenant, not across
 * the table. Two tenants ingesting the same row into a table of the same name each read their own,
 * as the application role under RLS.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class RowHashPerTenantRlsIT {

  private static final String V73 = "db/migration/connector/V73__row_hash_unique_per_tenant.sql";

  @Inject DynamicTableWriter writer;
  @Inject AgroalDataSource appDs;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @Test
  void two_tenants_ingesting_the_same_rows_each_read_them() throws Exception {
    String table = "t_rowhash1198_" + System.nanoTime();
    write(table, 1L);
    write(table, 2L);

    assertThat(rowsSeenBy(1L, table)).isEqualTo(2);
    assertThat(rowsSeenBy(2L, table)).isEqualTo(2);
  }

  @Test
  void a_table_created_before_the_upgrade_takes_a_second_tenants_rows_once_migrated() throws Exception {
    String table = "t_rowhash1198_old_" + System.nanoTime();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      // the shape every t_* table had before #1198
      st.execute("CREATE TABLE connector." + table + " (id BIGSERIAL PRIMARY KEY, _row_hash TEXT UNIQUE NOT NULL, "
          + "_ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), _source TEXT, _run_id UUID, _mapping_version INT, "
          + "tenant_id BIGINT NOT NULL, code TEXT)");
      try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(V73),
          V73 + " is not on the test classpath")) {
        String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        st.execute(sql);
        st.execute(sql);
      }
    }
    write(table, 1L);
    write(table, 2L);

    assertThat(rowsSeenBy(2L, table)).isEqualTo(2);
  }

  /** Two rows, identical whichever tenant writes them, with the hash the writer would compute. */
  private void write(String table, long tenant) {
    MappingTarget.Table spec = new MappingTarget.Table("connector", table, true, MappingTarget.OnConflict.SKIP, List.of());
    List<Map<String, Object>> rows = List.of(Map.of("code", "C-1"), Map.of("code", "C-2"));
    List<Map<String, Object>> sys = rows.stream().map(r -> {
      Map<String, Object> s = new HashMap<>();
      s.put("_row_hash", "h-" + r.get("code"));
      s.put("_mapping", "jdbc:" + table);
      s.put("_source", "jdbc:" + table);
      s.put("_mapping_version", 1);
      return s;
    }).toList();
    TenantContext.runAsSystem(() -> writer.write(spec, rows, sys, tenant));
  }

  private int rowsSeenBy(long tenant, String table) throws Exception {
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM connector." + table)) {
        rs.next();
        int n = rs.getInt(1);
        c.commit();
        return n;
      }
    }
  }
}
