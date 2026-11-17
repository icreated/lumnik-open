package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1198: a {@code t_*} table holds every tenant's rows, so a row is unique per tenant, not across
 * the table. Two tenants ingesting the same row into a table of the same name each read their own,
 * as the application role under RLS. #1990: so is a unique index a connector declares on it.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class RowHashPerTenantRlsIT {

  private static final String V73 = "db/migration/connector/V73__row_hash_unique_per_tenant.sql";
  private static final String V96 = "db/migration/connector/V96__declared_indexes_per_tenant.sql";
  private static final List<MappingTarget.Index> UNIQUE_CODE = List.of(new MappingTarget.Index(List.of("code"), true));

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
      replayTwice(st, V73);
    }
    write(table, 1L);
    write(table, 2L);

    assertThat(rowsSeenBy(2L, table)).isEqualTo(2);
  }

  /**
   * #1990: a unique index tenant B declares on code no longer refuses tenant A's row with the same code, and told A
   * that B held it. Every index on the table holds the tenant: led by tenant_id, or in its predicate.
   */
  @Test
  void a_declared_unique_index_is_per_tenant() throws Exception {
    String table = "t_declidx1990_" + System.nanoTime();
    write(table, 2L, UNIQUE_CODE);
    write(table, 1L, UNIQUE_CODE);

    assertThat(rowsSeenBy(1L, table)).isEqualTo(2);
    assertThat(rowsSeenBy(2L, table)).isEqualTo(2);
    assertThat(indexDefs(table)).as("every index of a shared table holds the tenant")
        .allSatisfy(def -> assertThat(def.contains("(tenant_id") || def.contains("WHERE")).as(def).isTrue())
        .anySatisfy(def -> assertThat(def).contains("UNIQUE INDEX").contains("(tenant_id, code)"));
  }

  /**
   * #1990: an index declared before the upgrade is rebuilt by V96 under its name, per tenant, unique as it was — the
   * long name too, which PostgreSQL cut at 63 bytes and the declared name no longer matches whole. A non-unique and
   * a two-column one too, tenant_id once whatever the old one listed, its collation and storage kept. What
   * createIndexes never made is left as it is — a hash index, a DESC column, an INCLUDE list, NULLS NOT DISTINCT — and
   * a table without a tenant_id column does not stop it. One a constraint holds, a foreign key references or the
   * replica identity uses cannot be dropped: it is left, and the migration names it in a WARNING.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 60})
  void a_declared_index_from_before_the_upgrade_is_rebuilt_per_tenant(int nameLength) throws Exception {
    String stem = "t_declidx1990_old_" + System.nanoTime();
    String table = nameLength == 0 ? stem : (stem + "x".repeat(nameLength)).substring(0, nameLength);
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("CREATE TABLE connector." + table + " (id BIGSERIAL PRIMARY KEY, _row_hash TEXT NOT NULL, "
          + "_ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), _source TEXT, _run_id UUID, _mapping_version INT, "
          + "tenant_id BIGINT NOT NULL, code TEXT, name TEXT, UNIQUE (tenant_id, _row_hash))");
      // what createIndexes built before #1990
      st.execute("CREATE UNIQUE INDEX \"" + table + "_idx_code\" ON connector." + table + " (code)");
      if (nameLength == 0) {   // a cut name holds one index: these would all be the same name
        st.execute("CREATE INDEX \"" + table + "_idx_name_tenant_id\" ON connector." + table + " (name, tenant_id)");
        // what it never made
        st.execute("CREATE INDEX \"" + table + "_idx_hashed\" ON connector." + table + " USING hash (code)");
        st.execute("CREATE INDEX \"" + table + "_idx_desc\" ON connector." + table + " (name DESC)");
        st.execute("CREATE INDEX \"" + table + "_idx_incl\" ON connector." + table + " (name) INCLUDE (code)");
        st.execute("CREATE UNIQUE INDEX \"" + table + "_idx_nnd\" ON connector." + table + " (name) NULLS NOT DISTINCT");
        st.execute("CREATE TABLE connector." + table + "_nt (code TEXT)");
        st.execute("CREATE INDEX \"" + table + "_nt_idx_code\" ON connector." + table + "_nt (code)");
        // made like createIndexes', then given a collation or storage of their own: rebuilt, keeping them
        st.execute("CREATE INDEX \"" + table + "_idx_coll\" ON connector." + table + " (name COLLATE \"C\")");
        st.execute("CREATE INDEX \"" + table + "_idx_ff\" ON connector." + table + " (code) WITH (fillfactor = 70)");
        st.execute("ALTER TABLE connector." + table + " CLUSTER ON \"" + table + "_idx_ff\"");
        st.execute("COMMENT ON INDEX connector.\"" + table + "_idx_ff\" IS 'kept'");
        // what cannot be dropped: left, and named
        st.execute("ALTER TABLE connector." + table + " ADD CONSTRAINT \"" + table + "_idx_held\" UNIQUE (name, code)");
        st.execute("CREATE UNIQUE INDEX \"" + table + "_idx_ref\" ON connector." + table + " (_source)");
        st.execute("CREATE TABLE connector." + table + "_side (src TEXT REFERENCES connector." + table + " (_source))");
        st.execute("CREATE UNIQUE INDEX \"" + table + "_idx_ri\" ON connector." + table + " (_row_hash, tenant_id)");
        st.execute("ALTER TABLE connector." + table + " REPLICA IDENTITY USING INDEX \"" + table + "_idx_ri\"");
      }
      String warnings = replayTwice(st, V96);
      if (nameLength == 0) {
        assertThat(warnings).contains(table + "_idx_held", table + "_idx_ref", table + "_idx_ri")
            .doesNotContain(table + "_idx_code", table + "_idx_coll", table + "_idx_ff");
      }
    }
    assertThat(indexDefs(table)).noneMatch(def -> def.endsWith("btree (code)") || def.endsWith("(name, tenant_id)"))
        .anySatisfy(def -> assertThat(def).contains("UNIQUE INDEX").contains("(tenant_id, code)"));
    if (nameLength == 0) {
      assertThat(indexDefs(table))
          .anySatisfy(def -> assertThat(def).doesNotContain("UNIQUE").contains("(tenant_id, name)"))
          .anySatisfy(def -> assertThat(def).contains("USING hash (code)"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (name DESC)"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (name) INCLUDE (code)"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (name) NULLS NOT DISTINCT"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (name, code)"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (_source)"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (_row_hash, tenant_id)"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (tenant_id, name COLLATE \"C\")"))
          .anySatisfy(def -> assertThat(def).endsWith("btree (tenant_id, code) WITH (fillfactor='70')"));
      assertThat(Jdbc.queryOne(adminDs, "kept", "SELECT ix.indisclustered::text || ' ' || obj_description(ix.indexrelid, "
              + "'pg_class') FROM pg_index ix JOIN pg_class ic ON ic.oid = ix.indexrelid WHERE ic.relname = ?",
              ps -> ps.setString(1, table + "_idx_ff"), rs -> rs.getString(1)))
          .as("the CLUSTER mark and the comment follow the rebuilt index").contains("true kept");
      assertThat(indexDefs(table + "_nt")).singleElement().asString().endsWith("(code)");
      // Left as they were, these still span tenants: out of the way of the writes below.
      Jdbc.update(adminDs, "drop", "DROP TABLE connector." + table + "_nt, connector." + table + "_side; "
          + "DROP INDEX connector.\"" + table + "_idx_nnd\", connector.\"" + table + "_idx_ref\"", ps -> {});
    }

    write(table, 2L, UNIQUE_CODE);
    write(table, 1L, UNIQUE_CODE);
    assertThat(rowsSeenBy(1L, table)).isEqualTo(2);
    assertThat(rowsSeenBy(2L, table)).isEqualTo(2);
  }

  /** Runs the shipped migration twice; returns the warnings of the first run. */
  private String replayTwice(Statement st, String migration) throws Exception {
    try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(migration),
        migration + " is not on the test classpath")) {
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      st.clearWarnings();
      st.execute(sql);
      StringBuilder warnings = new StringBuilder();
      for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) warnings.append(w.getMessage()).append('\n');
      st.execute(sql);
      return warnings.toString();
    }
  }

  private List<String> indexDefs(String table) {
    return Jdbc.query(adminDs, "indexdefs", "SELECT indexdef FROM pg_indexes WHERE schemaname = 'connector' "
        + "AND tablename = ? AND indexname NOT LIKE '%pkey'", ps -> ps.setString(1, table), rs -> rs.getString(1));
  }

  private void write(String table, long tenant) {
    write(table, tenant, List.of());
  }

  /** Two rows, identical whichever tenant writes them, with the hash the writer would compute. */
  private void write(String table, long tenant, List<MappingTarget.Index> indexes) {
    MappingTarget.Table spec = new MappingTarget.Table("connector", table, true, MappingTarget.OnConflict.SKIP, indexes);
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
