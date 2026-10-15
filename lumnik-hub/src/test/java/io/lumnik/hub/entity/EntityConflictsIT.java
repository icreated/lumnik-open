package io.lumnik.hub.entity;

import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedPgResource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * The aggregate over the fusion ledger: only rows whose sources DISAGREE appear; agreement
 * and gap-fill stay out; RLS keeps another tenant's rows invisible.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityConflictsIT {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject EntityRegistry registry;

  static final String MANIFEST = """
      kind: Entity
      name: ConflictProbe
      match: [customer_code]
      sources:
        - table: t_confl_erp
          map:
            cust_id: customer_code
            cust_name: name
        - table: t_confl_csv
          map:
            code: customer_code
            cname: name
            email: email
      """;

  @BeforeEach
  void seedAndApply() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_conflictprobe\"");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_csv");
      st.execute("CREATE TABLE connector.t_confl_erp (tenant_id bigint NOT NULL, \"cust_id\" text, \"cust_name\" text)");
      st.execute("CREATE TABLE connector.t_confl_csv (tenant_id bigint NOT NULL, \"code\" text, \"cname\" text, \"email\" text)");
      for (String t : new String[]{"t_confl_erp", "t_confl_csv"}) {
        st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
        st.execute("CREATE POLICY " + t + "_iso ON connector." + t
            + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
        st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app");
      }
      // C-100 agrees -> stays OUT of the aggregate
      st.execute("INSERT INTO connector.t_confl_erp VALUES (1,'C-100','Jean Dupont')");
      st.execute("INSERT INTO connector.t_confl_csv VALUES (1,'C-100','Jean Dupont','jd@x.fr')");
      // C-200 name CONFLICTS -> the one aggregate row
      st.execute("INSERT INTO connector.t_confl_erp VALUES (1,'C-200','Marie')");
      st.execute("INSERT INTO connector.t_confl_csv VALUES (1,'C-200','MARIE A.','m@x.fr')");
      // Tenant 2 has its own conflict -> must NOT leak into tenant 1's aggregate
      st.execute("INSERT INTO connector.t_confl_erp VALUES (2,'C-900','Bob')");
      st.execute("INSERT INTO connector.t_confl_csv VALUES (2,'C-900','Robert','b@x.fr')");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  /**
   * Applying a manifest persists a {@code connector.entity_manifest} row that outlives this class:
   * a later boot would reload manifests whose tables are long gone. Mirror the @BeforeEach drops
   * (plus the artifacts single tests create) and clear the persisted rows, as
   * {@code CustomerWowFusionIT} does. The in-memory registry keeps the applied entries for this
   * JVM — every other test filters its assertions by entity name — but not the parser-refused
   * manifest one test puts straight into it.
   */
  @AfterEach
  void cleanup() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_conflictprobe\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_brokenfirst\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_resolvedprobe\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_defectprobe\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_commitbomb\"");
      st.execute("DROP FUNCTION IF EXISTS connector.f_commit_bomb()");
      st.execute("DROP TABLE IF EXISTS connector.t_commit_bomb");
      st.execute("DROP FUNCTION IF EXISTS connector.f_commit_bomb_trip()");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_csv");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_broken");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_res_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_res_crm");
      st.execute("DELETE FROM core.entity_identity WHERE entity_type = 'resolvedprobe'");
      st.execute("DELETE FROM connector.entity_manifest WHERE name IN ('conflictprobe', 'brokenfirst', 'resolvedprobe')");
    }
    registry.remove("DefectProbe");
    registry.remove("CommitBomb");
  }

  @Test
  void aggregate_lists_only_disagreeing_rows() {
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("findAll { it.entity == 'ConflictProbe' }", hasSize(1))
        .body("find { it.entity == 'ConflictProbe' }.key", equalTo("C-200"))
        .body("find { it.entity == 'ConflictProbe' }.columns", equalTo(List.of("name")))
        .body("find { it.entity == 'ConflictProbe' }.conflicts.name.value", equalTo(List.of("Marie", "MARIE A.")));
  }

  @Test
  void rls_keeps_other_tenants_conflicts_invisible() {
    given().headers(TestIdentity.tenantAndUser(2, 2))
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("findAll { it.entity == 'ConflictProbe' && it.key == 'C-200' }", hasSize(0))
        .body("findAll { it.entity == 'ConflictProbe' && it.key == 'C-900' }", hasSize(1));
  }

  @Test
  void limit_caps_the_aggregate() {
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts?limit=1")
        .then().statusCode(200)
        .body("size()", Matchers.lessThanOrEqualTo(1));
  }

  @Test
  void a_missing_compiled_view_is_skipped_not_500() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_conflictprobe\"");
    }
    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("findAll { it.entity == 'ConflictProbe' }", hasSize(0));
  }

  static final String BROKEN_FIRST_MANIFEST = """
      kind: Entity
      name: BrokenFirst
      match: [code]
      sources:
        - table: t_confl_broken
          map:
            code: code
            val: val
      """;

  /**
   * "BrokenFirst" sorts before "ConflictProbe" (EntityRegistry.all() is case-insensitive-by-name),
   * so it is iterated FIRST. Its compiled view is dropped after apply, simulating the same
   * missing-view case as {@link #a_missing_compiled_view_is_skipped_not_500}, but this time with a
   * second, healthy entity iterated AFTER it: the probe skips BrokenFirst as missing, and
   * ConflictProbe's C-200 row still answers.
   */
  @Test
  void a_broken_view_earlier_in_iteration_order_does_not_blank_a_later_entity() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_brokenfirst\"");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_broken");
      st.execute("CREATE TABLE connector.t_confl_broken (tenant_id bigint NOT NULL, \"code\" text, \"val\" text)");
      st.execute("ALTER TABLE connector.t_confl_broken ENABLE ROW LEVEL SECURITY");
      st.execute("CREATE POLICY t_confl_broken_iso ON connector.t_confl_broken "
          + "USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
      st.execute("GRANT SELECT ON connector.t_confl_broken TO lumnik_app");
      // K-1 CONFLICTS within its own single source (two rows, same match key, differing val)
      st.execute("INSERT INTO connector.t_confl_broken VALUES (1,'K-1','a')");
      st.execute("INSERT INTO connector.t_confl_broken VALUES (1,'K-1','b')");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(BROKEN_FIRST_MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_brokenfirst\"");
    }

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("findAll { it.entity == 'BrokenFirst' }", hasSize(0))
        .body("find { it.entity == 'ConflictProbe' }.key", equalTo("C-200"));
  }

  /**
   * #929: BrokenFirst's view passes the probe but fails AT QUERY TIME — its source's grant is revoked and the
   * view is security_invoker. On Postgres a failed statement aborts the transaction it runs in, so the entity
   * iterated after it (ConflictProbe) must not be read in that same transaction.
   */
  @Test
  void a_view_failing_at_query_time_does_not_blank_a_later_entity() throws Exception {
    EntitySourceTableFixture.create(adminDs, "t_confl_broken", "v_brokenfirst", "code", "val");
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.t_confl_broken VALUES (1,'K-1','a'), (1,'K-1','b')");
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(BROKEN_FIRST_MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("REVOKE SELECT ON connector.t_confl_broken FROM lumnik_app");
    }

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts?limit=1000")
        .then().statusCode(200)
        .body("findAll { it.entity == 'BrokenFirst' }", hasSize(0))
        .body("find { it.entity == 'ConflictProbe' }.key", equalTo("C-200"));
  }

  /**
   * #890: the backstop takes database failures only. A defect in the read — here a manifest with neither match:
   * nor resolution:, which the parser refuses, put straight into the registry over a readable view — surfaces as
   * a 500 instead of being logged as an unreadable view and skipped.
   */
  @Test
  void a_programming_error_is_not_passed_off_as_an_unreadable_view() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("CREATE VIEW connector.\"v_defectprobe\" AS SELECT '{}'::jsonb AS _conflicts");
      st.execute("GRANT SELECT ON connector.\"v_defectprobe\" TO lumnik_app");
    }
    registry.put(new EntityManifest("Entity", "DefectProbe", null, null, List.of(), List.of(), null, null, null));

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts")
        .then().statusCode(500);
  }

  /**
   * A failure that surfaces at COMMIT rather than at a statement: QuarkusTransaction rethrows a statement's
   * RuntimeException as-is but wraps whatever commit() throws in a QuarkusTransactionException. CommitBomb's view
   * calls a function whose insert trips a deferred constraint trigger, so its read succeeds and its transaction
   * fails only when it commits. It sorts before ConflictProbe, which must still answer.
   */
  @Test
  void a_failure_at_commit_does_not_blank_a_later_entity() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("CREATE TABLE connector.t_commit_bomb (n int)");
      st.execute("CREATE FUNCTION connector.f_commit_bomb_trip() RETURNS trigger LANGUAGE plpgsql AS "
          + "$$ BEGIN RAISE EXCEPTION 'tripped at commit'; END $$");
      st.execute("CREATE CONSTRAINT TRIGGER commit_bomb AFTER INSERT ON connector.t_commit_bomb "
          + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION connector.f_commit_bomb_trip()");
      st.execute("CREATE FUNCTION connector.f_commit_bomb() RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER AS "
          + "$$ BEGIN INSERT INTO connector.t_commit_bomb VALUES (1); RETURN '{\"x\": []}'::jsonb; END $$");
      st.execute("GRANT EXECUTE ON FUNCTION connector.f_commit_bomb() TO lumnik_app");
      st.execute("CREATE VIEW connector.\"v_commitbomb\" AS SELECT 'k'::text AS code, connector.f_commit_bomb() AS _conflicts");
      st.execute("GRANT SELECT ON connector.\"v_commitbomb\" TO lumnik_app");
    }
    registry.put(new EntityManifest("Entity", "CommitBomb", null, List.of("code"), List.of(), List.of(), null, null, null));

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts?limit=1000")
        .then().statusCode(200)
        .body("findAll { it.entity == 'CommitBomb' }", hasSize(0))
        .body("find { it.entity == 'ConflictProbe' }.key", equalTo("C-200"));
  }

  /**
   * The aggregate skips only "this entity's view failed to read". A failure outside that read — here the existence
   * probe, denied as a DBA would deny it, standing in for a database that cannot answer at all — surfaces as a 500,
   * not as an empty 200 that reads "no conflicts".
   */
  @Test
  void a_failing_probe_surfaces_instead_of_answering_no_conflicts() throws Exception {
    asSuperuser("REVOKE SELECT ON information_schema.views FROM PUBLIC");
    try {
      given().headers(TestIdentity.tenantAndUser(1))
          .when().get("/api/entities/conflicts")
          .then().statusCode(500);
    } finally {
      asSuperuser("GRANT SELECT ON information_schema.views TO PUBLIC");
    }
  }

  private static void asSuperuser(String sql) throws Exception {
    String url = ConfigProvider.getConfig().getValue("quarkus.datasource.\"admin\".jdbc.url", String.class);
    try (Connection c = DriverManager.getConnection(url, RlsEnforcedPgResource.SUPERUSER,
             RlsEnforcedPgResource.SUPERUSER_PASSWORD);
         Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  static final String RESOLVED_MANIFEST = """
      kind: Entity
      name: ResolvedProbe
      sources:
        - table: t_confl_res_erp
          map:
            cust_id: customer_code
            cust_name: name
        - table: t_confl_res_crm
          map:
            contact_id: contact_code
            cname: name
      resolution:
        identity:
          t_confl_res_erp: customer_code
          t_confl_res_crm: contact_code
        cascade:
          - id: by-name
            exact: { fields: [name] }
      """;

  /** Two source tables and the resolution: manifest over them; the crosswalk is seeded by each test. */
  private void applyResolvedProbe() throws Exception {
    EntitySourceTableFixture.create(adminDs, "t_confl_res_erp", "v_resolvedprobe", "cust_id", "cust_name");
    EntitySourceTableFixture.create(adminDs, "t_confl_res_crm", "v_resolvedprobe", "contact_id", "cname");
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(RESOLVED_MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  /**
   * #890: a resolution: entity has no match: columns, so its conflict row is addressed by the identity its
   * view groups by — {@code _entity_id}. The ERP and the CRM resolved to ONE entity and disagree on its name.
   */
  @Test
  void a_resolution_entity_conflict_is_listed_under_its_entity_id() throws Exception {
    applyResolvedProbe();
    UUID entityId = UUID.randomUUID();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.t_confl_res_erp VALUES (1,'C-1','Marie')");
      st.execute("INSERT INTO connector.t_confl_res_crm VALUES (1,'K-1','MARIE A.')");
    }
    insertIdentity(adminDs, 1, "ResolvedProbe", "t_confl_res_erp", "C-1", entityId);
    insertIdentity(adminDs, 1, "ResolvedProbe", "t_confl_res_crm", "K-1", entityId);

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts?limit=1000")
        .then().statusCode(200)
        .body("findAll { it.entity == 'ResolvedProbe' }", hasSize(1))
        .body("find { it.entity == 'ResolvedProbe' }.key", equalTo(entityId.toString()))
        .body("find { it.entity == 'ResolvedProbe' }.columns", equalTo(List.of("name")))
        .body("find { it.entity == 'ResolvedProbe' }.conflicts.name.value", equalTo(List.of("Marie", "MARIE A.")));
  }

  /**
   * An unresolved row has no {@code _entity_id}; its address is the view's {@code _handle},
   * {@code <source table>:<source id>}, the two values of the row's identity joined (#928).
   * Here one source carries the same identity twice.
   */
  @Test
  void an_unresolved_resolution_entity_conflict_is_listed_under_its_source_handle() throws Exception {
    applyResolvedProbe();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO connector.t_confl_res_crm VALUES (1,'K-9','Paul'), (1,'K-9','Pierre')");
    }

    given().headers(TestIdentity.tenantAndUser(1))
        .when().get("/api/entities/conflicts?limit=1000")
        .then().statusCode(200)
        .body("findAll { it.entity == 'ResolvedProbe' }", hasSize(1))
        .body("find { it.entity == 'ResolvedProbe' }.key", equalTo("t_confl_res_crm:K-9"))
        .body("find { it.entity == 'ResolvedProbe' }.conflicts.name.value", equalTo(List.of("Paul", "Pierre")));
  }
}
