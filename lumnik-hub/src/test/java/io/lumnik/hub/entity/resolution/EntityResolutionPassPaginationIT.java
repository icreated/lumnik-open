package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pass pages through its rows {@code batch-size} at a time (#904: the winners computed once, then
 * read by key) — this class pins correctness across page boundaries through the real CDI pass and
 * resolver, with a batch size small enough ({@code 2}) to force several pages over a handful of rows.
 * {@code EntityResolutionPassWinnerSetIT} pins the exact rows handed to the resolver.
 */
@QuarkusTest
@TestProfile(EntityResolutionPassPaginationIT.SmallBatch.class)
class EntityResolutionPassPaginationIT {

  public static class SmallBatch implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("lumnik.entity.resolution.batch-size", "2");
    }
  }

  @Inject EntityResolutionPass pass;
  @Inject EntityRegistry registry;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY_TYPE = "respagit620";
  private static final String TABLE = "t_res_pag_it620_erp";

  private static final String MANIFEST = """
      kind: Entity
      name: ResPagIt620
      sources:
        - table: t_res_pag_it620_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_pag_it620_erp: customer_id
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
    registry.put(new EntityManifestParser().parse(MANIFEST));
  }

  private void insertRow(long tenant, String customerId, String email) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO connector." + TABLE + " (tenant_id, \"CUSTOMER_ID\", \"EMAIL_COL\") "
                 + "VALUES (?, ?, ?)")) {
      ps.setLong(1, tenant);
      ps.setString(2, customerId);
      ps.setString(3, email);
      ps.executeUpdate();
    }
  }

  @Test
  void replay_resolves_every_identity_exactly_once_across_several_pages() throws Exception {
    long tenant = 98_301;
    // 7 rows, distinct emails, batch size 2 => at least 4 pages (2+2+2+1).
    for (int i = 1; i <= 7; i++) {
      insertRow(tenant, "pg-" + i, "pg" + i + "@example.com");
    }

    pass.runReplay(tenant, "ResPagIt620");

    Set<UUID> entityIds = new HashSet<>();
    for (int i = 1; i <= 7; i++) {
      try (Connection c = adminDs.getConnection();
           PreparedStatement ps = c.prepareStatement(
               "SELECT state, entity_id FROM core.entity_identity "
                   + "WHERE tenant_id = ? AND entity_type = ? AND source = ? AND source_id = ?")) {
        ps.setLong(1, tenant);
        ps.setString(2, ENTITY_TYPE);
        ps.setString(3, TABLE);
        ps.setString(4, "pg-" + i);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).as("row pg-%d must have been resolved, not skipped by a page boundary", i)
              .isTrue();
          assertThat(rs.getString(1)).as("row pg-%d", i).isEqualTo("resolved");
          entityIds.add((UUID) rs.getObject(2));
        }
      }
    }
    // 7 distinct emails, no shared candidates => 7 distinct fresh-minted entities. A row
    // processed twice by a broken keyset boundary would still land here once (upsert is
    // idempotent by PK) — the real tell of double-processing or a skipped row is either a
    // missing row above (already asserted) or two identities silently sharing one entity_id
    // without ever sharing an email, which the row count here already rules out.
    assertThat(entityIds).hasSize(7);
  }
}
