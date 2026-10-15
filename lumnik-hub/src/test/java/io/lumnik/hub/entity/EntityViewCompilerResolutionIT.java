package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * #618 — {@code EntityViewCompiler}'s resolution: projection, proved against a real Postgres under
 * {@link RlsEnforcedProfile}: the shared DevServices container connects as the table owner, which
 * would pass every one of these even with a missing RLS policy or a missing grant.
 *
 * <p>The manifest is applied through {@code compiler.apply} directly, the {@code
 * EntityNameOverwriteScopeIT} idiom — this class exercises the compiler's own projection output in
 * isolation from the door and the driving loop that actually populates the crosswalk (#620), which
 * {@code EntityApplyIT.apply_of_a_resolution_manifest_succeeds} and {@code
 * EntityResolutionPassIT}/{@code EntityResolveResourceIT} cover respectively.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityViewCompilerResolutionIT {

  private static final long T1 = 96181;
  private static final long T2 = 96182;
  private static final String ENTITY_TYPE = "resit618";
  private static final String ERP = "t_res_it618_erp";
  private static final String CRM = "t_res_it618_crm";
  private static final String VIEW = "connector.\"v_resit618\"";

  @Inject EntityViewCompiler compiler;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject
  AgroalDataSource appDs; // default datasource: lumnik_app, RLS-enforced

  @Inject
  @DataSource("readonly")
  AgroalDataSource readonlyDs;

  private static final String MANIFEST = """
      kind: Entity
      name: ResIt618
      sources:
        - table: t_res_it618_erp
          map:
            CUSTOMER_ID: customer_id
            NAME_COL: name
        - table: t_res_it618_crm
          map:
            CONTACT_ID: contact_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_it618_erp: customer_id
          t_res_it618_crm: contact_id
        cascade:
          - id: erp-key
            exact: { fields: [customer_id] }
      """;

  @BeforeAll
  void applyResolvedManifest() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.entity_manifest WHERE name = '" + ENTITY_TYPE + "'");
      st.execute("DROP VIEW IF EXISTS " + VIEW);
      st.execute("DROP TABLE IF EXISTS connector." + ERP);
      st.execute("DROP TABLE IF EXISTS connector." + CRM);
      st.execute("CREATE TABLE connector." + ERP
          + " (tenant_id bigint NOT NULL, \"CUSTOMER_ID\" text, \"NAME_COL\" text)");
      st.execute("CREATE TABLE connector." + CRM
          + " (tenant_id bigint NOT NULL, \"CONTACT_ID\" text, \"EMAIL_COL\" text)");
      // Same tenant_isolation shape production dynamic tables get from RlsDdl — a hand-made test
      // table starts with RLS off, and this suite's whole point is to read through lumnik_app/
      // lumnik_readonly, so without this every row of every tenant would be visible to both.
      for (String t : List.of("connector." + ERP, "connector." + CRM)) {
        st.execute("ALTER TABLE " + t + " ENABLE ROW LEVEL SECURITY");
        st.execute("CREATE POLICY tenant_isolation ON " + t
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        st.execute("GRANT SELECT ON " + t + " TO lumnik_app");
        st.execute("GRANT SELECT ON " + t + " TO lumnik_readonly");
      }
    }

    EntityManifest m = new EntityManifestParser().parse(MANIFEST);
    ScopeGuardedUpsert outcome = compiler.apply(m, MANIFEST, null);
    assertThat(outcome).as("setup: compiler.apply must succeed").isInstanceOf(ScopeGuardedUpsert.Applied.class);
  }

  private void insertErp(long tenant, String customerId, String name) throws Exception {
    try (Connection c = adminDs.getConnection();
         var ps = c.prepareStatement(
             "INSERT INTO connector." + ERP + " (tenant_id, \"CUSTOMER_ID\", \"NAME_COL\") VALUES (?, ?, ?)")) {
      ps.setLong(1, tenant);
      ps.setString(2, customerId);
      ps.setString(3, name);
      ps.executeUpdate();
    }
  }

  private void insertCrm(long tenant, String contactId, String email) throws Exception {
    try (Connection c = adminDs.getConnection();
         var ps = c.prepareStatement(
             "INSERT INTO connector." + CRM + " (tenant_id, \"CONTACT_ID\", \"EMAIL_COL\") VALUES (?, ?, ?)")) {
      ps.setLong(1, tenant);
      ps.setString(2, contactId);
      ps.setString(3, email);
      ps.executeUpdate();
    }
  }

  private void insertIdentity(long tenant, String source, String sourceId, String state, UUID entityId)
      throws Exception {
    insertIdentity(ENTITY_TYPE, tenant, source, sourceId, state, entityId);
  }

  private void insertIdentity(String entityType, long tenant, String source, String sourceId, String state,
                              UUID entityId) throws Exception {
    try (Connection c = adminDs.getConnection();
         var ps = c.prepareStatement("INSERT INTO core.entity_identity "
             + "(tenant_id, entity_type, source, source_id, state, entity_id) VALUES (?, ?, ?, ?, ?, ?)")) {
      ps.setLong(1, tenant);
      ps.setString(2, entityType);
      ps.setString(3, source);
      ps.setString(4, sourceId);
      ps.setString(5, state);
      if (entityId == null) ps.setNull(6, Types.OTHER); else ps.setObject(6, entityId);
      ps.executeUpdate();
    }
  }

  private record Row(String entityId, String resolution, String customerId, String contactId,
                      String name, String email) {}

  private List<Row> queryAsTenant(AgroalDataSource ds, long tenant) throws Exception {
    List<Row> rows = new ArrayList<>();
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("BEGIN");
      st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
      try (ResultSet rs = st.executeQuery(
          "SELECT \"_entity_id\"::text, \"_resolution\", \"customer_id\", \"contact_id\", \"name\", \"email\" "
          + "FROM " + VIEW)) {
        while (rs.next()) {
          rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
              rs.getString(5), rs.getString(6)));
        }
      }
      st.execute("COMMIT");
    }
    return rows;
  }

  @Test
  void unresolved_source_rows_appear_as_separate_groups_and_the_row_count_is_preserved() throws Exception {
    long tenant = 96201;
    insertErp(tenant, "unr-1", "Alice");
    insertErp(tenant, "unr-2", "Bob");
    insertErp(tenant, "unr-3", "Carol");
    // No core.entity_identity row for any of them: an INNER join would have dropped all three,
    // and a bare GROUP BY entity_id would have folded them into one NULL group.

    List<Row> rows = queryAsTenant(appDs, tenant);
    assertThat(rows).hasSize(3);
    assertThat(rows).extracting(Row::customerId).containsExactlyInAnyOrder("unr-1", "unr-2", "unr-3");
    assertThat(rows).allSatisfy(r -> {
      assertThat(r.entityId()).as("nothing has resolved this row yet").isNull();
      assertThat(r.resolution()).isNull();
    });
  }

  @Test
  void resolved_rows_from_two_sources_fuse_into_one_row_by_canonical_entity_id() throws Exception {
    long tenant = 96202;
    UUID entity = UUID.randomUUID();
    insertErp(tenant, "cust-1", "Alice");
    insertCrm(tenant, "cont-1", "alice@example.com");
    insertIdentity(tenant, ERP, "cust-1", "resolved", entity);
    insertIdentity(tenant, CRM, "cont-1", "resolved", entity);

    List<Row> rows = queryAsTenant(appDs, tenant);
    assertThat(rows).as("two source rows resolved to one entity fuse into one row").hasSize(1);
    Row row = rows.get(0);
    assertThat(row.entityId()).isEqualTo(entity.toString());
    assertThat(row.resolution()).isEqualTo("resolved");
    assertThat(row.customerId()).isEqualTo("cust-1");
    assertThat(row.contactId()).isEqualTo("cont-1");
    assertThat(row.name()).isEqualTo("Alice");
    assertThat(row.email()).isEqualTo("alice@example.com");
  }

  @Test
  void a_tenant_never_sees_another_tenants_resolved_link_through_the_join() throws Exception {
    // Both tenants happen to carry the same raw identity value — only tenant 2 has ever been
    // resolved. Without RLS on core.entity_identity, tenant 1's row would silently pick up
    // tenant 2's entity_id through the join: the crosswalk would become a cross-tenant oracle.
    String sharedRawId = "shared-96203";
    insertErp(T1, sharedRawId, "T1 name");
    insertErp(T2, sharedRawId, "T2 name");
    insertIdentity(T2, ERP, sharedRawId, "resolved", UUID.randomUUID());

    List<Row> asT1 = queryAsTenant(appDs, T1);
    Row t1Row = asT1.stream().filter(r -> sharedRawId.equals(r.customerId())).findFirst().orElseThrow();
    assertThat(t1Row.entityId())
        .as("tenant 1 must not see tenant 2's resolution of the same raw identity value").isNull();
    assertThat(t1Row.resolution()).isNull();
  }

  @Test
  void the_readonly_role_can_read_the_resolved_view_without_permission_denied() throws Exception {
    // Ask-to-SQL executes as lumnik_readonly (SqlGuard layer 1). core.entity_identity itself is
    // only default-privileged to lumnik_app (01-roles.sh) — V15's ALTER DEFAULT PRIVILEGES for
    // lumnik_readonly is scoped to schema connector, not core — so without an explicit grant on
    // core.entity_identity, a security_invoker view joining it is unreadable there even though
    // the view's own GRANT SELECT succeeded.
    long tenant = 96204;
    insertErp(tenant, "ro-1", "Dave");

    assertThatCode(() -> queryAsTenant(readonlyDs, tenant))
        .as("lumnik_readonly must be able to read core.entity_identity through the resolved view")
        .doesNotThrowAnyException();
  }

  /**
   * #967: the issue's manifest, its ERP source carrying columns named like core.entity_identity's own —
   * {@code state}, {@code source}, {@code created_at}, and the identity column itself {@code source_id}, so
   * the {@code _source_id} projection, the join condition and the {@code IS NOT NULL} filter are each
   * exercised too. Unqualified next to {@code LEFT JOIN core.entity_identity ei}, CREATE VIEW failed with
   * "column reference … is ambiguous"; now the view is created and reads the SOURCE's value, not ei's.
   */
  @Test
  void a_source_column_named_like_a_crosswalk_column_compiles_and_reads_the_source_value() throws Exception {
    String crm = "t_res_it967_crm";
    String erp = "t_res_it967_erp";
    String view = "connector.\"v_resit967\"";
    String manifest = """
        kind: Entity
        name: ResIt967
        sources:
          - table: t_res_it967_crm
            map: { cust_id: customer_id, name: name, email: email, status: status, region_code: region_code }
          - table: t_res_it967_erp
            map: { source_id: customer_id, full_name: name, mail: email, state: status, region: region_code,
                   source: origin, created_at: created }
        resolution:
          identity:
            t_res_it967_crm: customer_id
            t_res_it967_erp: customer_id
          cascade:
            - id: key
              exact: { fields: [customer_id] }
        """;
    long tenant = 96205;
    EntitySourceTableFixture.create(adminDs, crm, "v_resit967", "cust_id", "name", "email", "status", "region_code");
    EntitySourceTableFixture.create(adminDs, erp, "v_resit967",
        "source_id", "full_name", "mail", "state", "region", "source", "created_at");
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.entity_manifest WHERE name = 'resit967'");
      st.execute("INSERT INTO connector." + crm + " VALUES (" + tenant + ", 'c-1', 'Alice', 'a@example.com', NULL, 'EU')");
      st.execute("INSERT INTO connector." + erp + " VALUES"
          + " (" + tenant + ", 'e-1', 'Alice', 'a@example.com', 'ACTIVE', 'EU', 'legacy-erp', '2001-02-03'),"
          + " (" + tenant + ", 'e-2', 'Bob', 'b@example.com', 'DORMANT', 'US', 'legacy-erp', '2004-05-06'),"
          + " (" + tenant + ", NULL, 'Nobody', NULL, 'ACTIVE', NULL, 'legacy-erp', NULL)");
    }
    UUID entity = UUID.randomUUID();
    insertIdentity("resit967", tenant, crm, "c-1", "resolved", entity);
    insertIdentity("resit967", tenant, erp, "e-1", "resolved", entity);

    EntityManifest m = new EntityManifestParser().parse(manifest);
    assertThat(compiler.apply(m, manifest, null)).isInstanceOf(ScopeGuardedUpsert.Applied.class);

    List<List<String>> rows = new ArrayList<>();
    try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("BEGIN");
      st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
      try (ResultSet rs = st.executeQuery("SELECT \"_entity_id\"::text, \"_resolution\", \"name\", \"status\","
          + " \"origin\", \"created\" FROM " + view + " ORDER BY \"name\"")) {
        while (rs.next()) {
          rows.add(Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
              rs.getString(5), rs.getString(6)));
        }
      }
      st.execute("COMMIT");
    }
    // ei.state would read 'resolved', ei.source the table name, ei.created_at the insert time; the
    // NULL-identity row is filtered on the source's own column.
    assertThat(rows).containsExactly(
        Arrays.asList(entity.toString(), "resolved", "Alice", "ACTIVE", "legacy-erp", "2001-02-03"),
        Arrays.asList(null, null, "Bob", "DORMANT", "legacy-erp", "2004-05-06"));
  }
}
