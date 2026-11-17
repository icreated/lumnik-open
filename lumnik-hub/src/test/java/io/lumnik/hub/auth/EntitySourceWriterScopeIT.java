package io.lumnik.hub.auth;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.RegisteredTables;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * #2048: a caller bound to scopes applies an Entity only within its tables' writers' scopes — every connector of the
 * tenant writing a source or lookup carries every scope the Entity declares, and one does. Entity reads ask
 * {@code allowsAny}, so a declared scope a writer lacks would hand that writer's rows to its holders. A tenant-wide
 * caller is unchanged. Real signed JWTs, because a header identity cannot carry a scope binding.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class EntitySourceWriterScopeIT {

  private static final UUID HR = UUID.fromString("20480000-0000-0000-0000-000000000001");
  private static final UUID MAGASIN = UUID.fromString("20480000-0000-0000-0000-000000000002");
  /** Tenant 2's hr connector writing t_w2048_mag too: another tenant's writers are not judged for this one. */
  private static final UUID OTHER_TENANT = UUID.fromString("20480000-0000-0000-0000-000000000003");
  private static final String[] TABLES = {"t_w2048_hr", "t_w2048_mag", "t_w2048_both", "t_w2048_none"};

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @BeforeEach
  void seed() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      clean(st);
      for (String t : TABLES) {
        st.execute("CREATE TABLE connector." + t + " (tenant_id bigint NOT NULL DEFAULT 1, pid text, label text)");
        st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app");
      }
      connector(st, HR, "w2048-hr", "hr");
      connector(st, MAGASIN, "w2048-mag", "magasin");
      register(st, HR, "t_w2048_hr");
      st.execute("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, sync_mode, "
          + "skip_policy) VALUES ('" + OTHER_TENANT + "', 2, 'w2048-t2', 'jdbc-generic', '{}'::jsonb, true, "
          + "ARRAY['hr'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
      st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, "
          + "connector_id) VALUES (2, 'jdbc:t_w2048_mag', 'connector', 't_w2048_mag', '{}'::jsonb, '" + OTHER_TENANT + "')");
      register(st, MAGASIN, "t_w2048_mag");
      register(st, HR, "t_w2048_both");
      register(st, MAGASIN, "t_w2048_both");
    }
    for (String t : TABLES) RegisteredTables.register(adminDs, 1L, t);
  }

  /** The connector rows are tenant 1's: other ITs list and count them. */
  @AfterEach
  void dropWriters() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      clean(st);
    }
  }

  private static void clean(Statement st) throws Exception {
    st.execute("DELETE FROM connector.entity_manifest WHERE name = 'w2048'");
    st.execute("DROP VIEW IF EXISTS connector.\"v_w2048\"");
    st.execute("DELETE FROM connector.dynamic_table WHERE table_name LIKE 't_w2048_%'");
    st.execute("DELETE FROM connector.connector WHERE id IN ('" + HR + "', '" + MAGASIN + "', '" + OTHER_TENANT + "')");
    for (String t : TABLES) st.execute("DROP TABLE IF EXISTS connector." + t);
  }

  @Test
  void a_table_another_scopes_connector_writes_is_refused() {
    apply(Set.of("lm_admin", "scope:magasin"), source("t_w2048_hr")).statusCode(403)
        .body("error", equalTo("scope not granted"));
  }

  /** A hub table answers a bound caller as any table no connector of theirs writes: the uniform 403. */
  @Test
  void a_hub_table_is_refused_like_one_out_of_scope() {
    apply(Set.of("lm_admin", "scope:magasin"), source("saved_view")).statusCode(403);
  }

  @Test
  void a_table_the_callers_scope_writes_is_applied() {
    apply(Set.of("lm_admin", "scope:magasin"), source("t_w2048_mag")).statusCode(200);
  }

  /** Every writer counts: hr's rows are in it, so no scope but one every writer carries may publish it. */
  @Test
  void a_table_written_in_two_scopes_is_refused_even_to_a_caller_holding_both() {
    Set<String> both = Set.of("lm_admin", "scope:magasin", "scope:hr");
    apply(both, source("t_w2048_both")).statusCode(403);
    apply(both, source("t_w2048_both", "[hr, magasin]")).statusCode(403);
  }

  /** The Entity's scopes are judged, not the caller's: holding hr too does not let magasin's table go to hr. */
  @Test
  void a_scope_the_writers_lack_is_refused_whatever_the_caller_holds() {
    apply(Set.of("lm_admin", "scope:magasin", "scope:hr"), source("t_w2048_mag", "[hr]")).statusCode(403);
  }

  /** A table that does not exist answers as one the caller may not read: the refusal tells nothing of either. */
  @Test
  void a_table_not_made_yet_is_refused_like_one_out_of_scope() {
    apply(Set.of("lm_admin", "scope:magasin"), source("t_w2048_never")).statusCode(403);
  }

  /** No connector of the tenant writes it: the caller reads no registration of it, as at the lm data doors. */
  @Test
  void a_table_no_connector_of_the_tenant_writes_is_refused() {
    apply(Set.of("lm_admin", "scope:magasin"), source("t_w2048_none")).statusCode(403);
  }

  @Test
  void a_lookup_another_scopes_connector_writes_is_refused() {
    String lookup = source("t_w2048_mag") + """
        joins:
          - table: t_w2048_hr
            from: product_id
            to: pid
            map: { label: hr_label }
        """;
    apply(Set.of("lm_admin", "scope:magasin"), lookup).statusCode(403);
  }

  /** The tenant-wide integrator publishes a scoped connector's table under the scopes they choose, as before. */
  @Test
  void a_tenant_wide_caller_is_unchanged() {
    apply(Set.of("lm_admin"), source("t_w2048_hr")).statusCode(200);
    apply(Set.of("lm_admin"), source("t_w2048_none")).statusCode(200);
  }

  private static String source(String table) {
    return source(table, "[magasin]");
  }

  private static String source(String table, String scopes) {
    return """
        kind: Entity
        name: W2048
        scopes: %s
        match: [product_id]
        sources:
          - table: %s
            map: { pid: product_id }
        """.formatted(scopes, table);
  }

  private static ValidatableResponse apply(Set<String> roles, String yaml) {
    return given().config(YamlBody.CONFIG)
        .header("Authorization", "Bearer " + NoBypassOidcProfile.token(roles, null))
        .contentType("application/yaml").body(yaml)
        .post("/api/entities/apply")
        .then();
  }

  private static void connector(Statement st, UUID id, String name, String scope) throws Exception {
    st.execute("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, sync_mode, "
        + "skip_policy) VALUES ('" + id + "', 1, '" + name + "', 'jdbc-generic', '{}'::jsonb, true, ARRAY['" + scope
        + "'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
  }

  private static void register(Statement st, UUID connector, String table) throws Exception {
    st.execute("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred, "
        + "connector_id) VALUES (1, 'jdbc:" + table + "', 'connector', '" + table + "', '{}'::jsonb, '" + connector + "')");
  }
}
