package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.search.mark.MatchKeyEncoding;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #671 design doc §7 test 5 — proves {@code EntityViewCompiler}'s hand-built {@code
 * _canonical_key} SQL expression (Task 5) and {@link MatchKeyEncoding#encode} agree, against a
 * real compiled {@code match:} view over a real Postgres under {@link RlsEnforcedProfile}: two
 * independent encodings of the same tuple are exactly the kind of thing that can drift silently,
 * so this is proved live rather than by inspection.
 *
 * <p>Follows the {@code EntityViewCompilerResolutionIT} idiom: the manifest is applied directly
 * through {@code compiler.apply}, and reads go through {@code appDs} (the RLS-enforced {@code
 * default} datasource) with an explicit {@code SET LOCAL app.current_tenant} per read, exactly
 * like that class's {@code queryAsTenant}.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchKeyEncodingViewParityIT {

  private static final long TENANT = 96711;
  private static final String ENTITY_TYPE = "parityit671";
  private static final String SRC = "t_" + ENTITY_TYPE + "_src";
  private static final String VIEW = "connector.\"v_" + ENTITY_TYPE + "\"";

  @Inject EntityViewCompiler compiler;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject
  AgroalDataSource appDs; // default datasource: lumnik_app, RLS-enforced

  private static final String MANIFEST = """
      kind: Entity
      name: Parityit671
      match: [code, region]
      sources:
        - table: t_parityit671_src
          map:
            CODE_COL: code
            REGION_COL: region
      """;

  @BeforeAll
  void applyManifest() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.entity_manifest WHERE name = '" + ENTITY_TYPE + "'");
      st.execute("DROP VIEW IF EXISTS " + VIEW);
      st.execute("DROP TABLE IF EXISTS connector." + SRC);
      st.execute("CREATE TABLE connector." + SRC
          + " (tenant_id bigint NOT NULL, \"CODE_COL\" text, \"REGION_COL\" text)");
      // Same tenant_isolation shape production dynamic tables get from RlsDdl — a hand-made test
      // table starts with RLS off, and this suite's whole point is to read through lumnik_app, so
      // without this every tenant's rows would be visible to every other tenant.
      st.execute("ALTER TABLE connector." + SRC + " ENABLE ROW LEVEL SECURITY");
      st.execute("CREATE POLICY tenant_isolation ON connector." + SRC
          + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
      st.execute("GRANT SELECT ON connector." + SRC + " TO lumnik_app");
      st.execute("GRANT SELECT ON connector." + SRC + " TO lumnik_readonly");
    }

    EntityManifest m = new EntityManifestParser().parse(MANIFEST);
    ScopeGuardedUpsert outcome = compiler.apply(m, MANIFEST, null);
    assertThat(outcome).as("setup: compiler.apply must succeed").isInstanceOf(ScopeGuardedUpsert.Applied.class);
  }

  private void insertRow(String code, String region) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector." + SRC + " (tenant_id, \"CODE_COL\", \"REGION_COL\") VALUES (?, ?, ?)")) {
      ps.setLong(1, TENANT);
      ps.setString(2, code);
      ps.setString(3, region);
      ps.executeUpdate();
    }
  }

  private String readCanonicalKey(String code, String region) throws Exception {
    try (Connection c = appDs.getConnection(); Statement begin = c.createStatement()) {
      begin.execute("BEGIN");
      begin.execute("SET LOCAL app.current_tenant = '" + TENANT + "'");
      try (PreparedStatement ps = c.prepareStatement(
          "SELECT \"_canonical_key\" FROM " + VIEW + " WHERE \"code\" = ? AND \"region\" = ?")) {
        ps.setString(1, code);
        ps.setString(2, region);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).as("row for code=%s region=%s must exist", code, region).isTrue();
          String key = rs.getString(1);
          begin.execute("COMMIT");
          return key;
        }
      }
    }
  }

  @Test
  void sqlExpressionMatchesJavaEncodingForSampleTuples() throws Exception {
    List<String[]> samples = List.of(
        new String[] {"abc", "eu"},
        new String[] {"a|b", "eu:west"},  // both encoding separators present in the VALUE
        new String[] {"", "eu"},          // empty component
        new String[] {"héllo", "日本"}     // multibyte UTF-8: octet_length must match Java's UTF-8 byte count
    );

    for (String[] s : samples) {
      insertRow(s[0], s[1]);
      String expected = MatchKeyEncoding.encode(List.of(s[0], s[1]));
      String actual = readCanonicalKey(s[0], s[1]);
      assertThat(actual).as("tuple %s/%s", s[0], s[1]).isEqualTo(expected);
    }
  }
}
