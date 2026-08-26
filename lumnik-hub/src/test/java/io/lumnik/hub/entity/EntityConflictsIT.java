package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * The aggregate over the fusion ledger: only rows whose sources DISAGREE appear; agreement
 * and gap-fill stay out; RLS keeps another tenant's rows invisible.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityConflictsIT {

  @Inject
  @io.quarkus.agroal.DataSource("admin")
  AgroalDataSource adminDs;

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

  static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config().encoderConfig(
      EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));

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
    given().config(YAML_CFG).header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType("application/yaml").body(MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);
  }

  /**
   * Applying a manifest persists a {@code connector.entity_manifest} row that outlives this class:
   * a later boot would reload manifests whose tables are long gone. Mirror the @BeforeEach drops
   * (plus the BrokenFirst artifacts one test creates) and clear the persisted rows, as
   * {@code CustomerWowFusionIT} does. The in-memory registry keeps its entries for this JVM —
   * it has no remove — but every other test filters its assertions by entity name.
   */
  @AfterEach
  void cleanup() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_conflictprobe\"");
      st.execute("DROP VIEW IF EXISTS connector.\"v_brokenfirst\"");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_erp");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_csv");
      st.execute("DROP TABLE IF EXISTS connector.t_confl_broken");
      st.execute("DELETE FROM connector.entity_manifest WHERE name IN ('conflictprobe', 'brokenfirst')");
    }
  }

  @Test
  void aggregate_lists_only_disagreeing_rows() {
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("findAll { it.entity == 'ConflictProbe' }", hasSize(1))
        .body("find { it.entity == 'ConflictProbe' }.key", equalTo("C-200"))
        .body("find { it.entity == 'ConflictProbe' }.columns", equalTo(java.util.List.of("name")))
        .body("find { it.entity == 'ConflictProbe' }.conflicts.name.value", equalTo(java.util.List.of("Marie", "MARIE A.")));
  }

  @Test
  void rls_keeps_other_tenants_conflicts_invisible() {
    given().header("X-Tenant-ID", "2").header("X-User-ID", "2")
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("findAll { it.entity == 'ConflictProbe' && it.key == 'C-200' }", hasSize(0))
        .body("findAll { it.entity == 'ConflictProbe' && it.key == 'C-900' }", hasSize(1));
  }

  @Test
  void limit_caps_the_aggregate() {
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .when().get("/api/entities/conflicts?limit=1")
        .then().statusCode(200)
        .body("size()", org.hamcrest.Matchers.lessThanOrEqualTo(1));
  }

  @Test
  void a_missing_compiled_view_is_skipped_not_500() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_conflictprobe\"");
    }
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
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
   * second, healthy entity iterated AFTER it. On Postgres a failed statement aborts the whole
   * transaction, so without the information_schema probe, ConflictProbe's C-200 row would be
   * silently lost too — the probe is what keeps it alive.
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
    given().config(YAML_CFG).header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType("application/yaml").body(BROKEN_FIRST_MANIFEST)
        .post("/api/entities/apply").then().statusCode(200);

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP VIEW IF EXISTS connector.\"v_brokenfirst\"");
    }

    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .when().get("/api/entities/conflicts")
        .then().statusCode(200)
        .body("findAll { it.entity == 'BrokenFirst' }", hasSize(0))
        .body("find { it.entity == 'ConflictProbe' }.key", equalTo("C-200"));
  }
}
