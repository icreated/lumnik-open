package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.writer.DynamicTableWriter;
import io.lumnik.testkit.auth.TestIdentity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * #2048: an entity's scopes hold for a writer that comes after it. A connector starts writing a table an entity reads
 * only if it carries every scope the entity declares; otherwise its rows would reach readers it does not serve. A
 * writer already registered keeps writing.
 */
@QuarkusTest
class EntityNewWriterScopeIT {

  private static final String TABLE = "t_nw2048";
  private static final UUID MAGASIN = UUID.fromString("20480000-0000-0000-0000-0000000000a1");
  private static final UUID HR = UUID.fromString("20480000-0000-0000-0000-0000000000a2");
  private static final UUID BOTH = UUID.fromString("20480000-0000-0000-0000-0000000000a3");
  private static final UUID ELSEWHERE = UUID.fromString("20480000-0000-0000-0000-0000000000a4");

  @Inject DynamicTableWriter writer;
  @Inject EntityViewCompiler compiler;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @BeforeEach
  void magasinWritesTheTableAndAnEntityReadsIt() {
    clean();
    sql(connector(MAGASIN, "nw-magasin", "'magasin'"));
    sql(connector(HR, "nw-hr", "'hr'"));
    sql(connector(BOTH, "nw-both", "'hr', 'magasin'"));
    write(MAGASIN, "h1");
    apply("[magasin]");
  }

  @AfterEach
  void clean() {
    sql("DELETE FROM connector.entity_manifest WHERE name = 'nw2048'");
    sql("DROP VIEW IF EXISTS connector.\"v_nw2048\"");
    sql("DELETE FROM connector.dynamic_table WHERE table_name = '" + TABLE + "'");
    sql("DROP TABLE IF EXISTS connector." + TABLE);
    sql("DELETE FROM connector.connector WHERE id IN ('" + MAGASIN + "', '" + HR + "', '" + BOTH + "', '" + ELSEWHERE + "')");
  }

  /** Its writer gone, the table taken by a connector of another scope: magasin's readers would read hr's rows. */
  @Test
  void a_new_writer_lacking_an_entity_scope_is_refused() {
    sql("DELETE FROM connector.dynamic_table WHERE connector_id = '" + MAGASIN + "'");

    assertThatThrownBy(() -> write(HR, "h2"))
        .hasMessageContaining("connector.t_nw2048 is read by an entity whose scopes connector 'nw-hr' would not all carry");
  }

  @Test
  void a_new_writer_carrying_every_entity_scope_writes() {
    sql("DELETE FROM connector.dynamic_table WHERE connector_id = '" + MAGASIN + "'");

    assertThatCode(() -> write(BOTH, "h2")).doesNotThrowAnyException();
  }

  /** A writer's scopes narrowed under an entity reading its table: the same breach as a new writer, refused. */
  @Test
  void a_writer_retagged_out_of_an_entity_scope_is_refused() {
    retag(MAGASIN, List.of("hr")).statusCode(400)
        .body("error", containsString("read by an entity whose scopes connector 'nw-magasin' would not all carry"))
        .body("error", not(containsString("nw2048")));
    retag(MAGASIN, List.of("magasin", "hr")).statusCode(200);
  }

  /**
   * The entity is re-scoped wider afterwards, by a tenant-wide integrator: a sharing they chose. The writer in place
   * keeps writing, and its scopes may be set again or widened: only a breach the new scopes make is refused.
   */
  @Test
  void a_sharing_chosen_by_a_tenant_wide_integrator_keeps_working() {
    apply("[magasin, hr]");

    assertThatCode(() -> write(MAGASIN, "h3")).doesNotThrowAnyException();
    retag(MAGASIN, List.of("magasin")).statusCode(200);
    retag(MAGASIN, List.of("magasin", "x")).statusCode(200);
    retag(MAGASIN, List.of("x")).statusCode(400);   // out of magasin, which its entity serves
  }

  /**
   * #2050: the entity is tenant 1's, so another tenant's rows never reach its readers (RLS on the table): a writer of
   * that tenant is not refused for it, and learns nothing of it.
   */
  @Test
  void another_tenants_entity_does_not_refuse_a_writer() {
    sql(connector(ELSEWHERE, 2, "nw-elsewhere", "'hr'"));

    assertThatCode(() -> write(ELSEWHERE, 2L, "h4")).doesNotThrowAnyException();
  }

  private static ValidatableResponse retag(UUID connector, List<String> tags) {
    return given().headers(TestIdentity.tenantAndUser(1)).contentType(ContentType.JSON).body(tags)
        .put("/api/platform/connectors/" + connector + "/tags").then();
  }

  private void apply(String scopes) {
    String yaml = """
        kind: Entity
        name: NW2048
        scopes: %s
        match: [code]
        sources:
          - table: %s
            map: { code: code }
        """.formatted(scopes, TABLE);
    compiler.apply(new EntityManifestParser().parse(yaml), yaml, null, 1L);
  }

  /** One chunk, one row, committed, as a connector's run writes it. */
  private void write(UUID connector, String hash) {
    write(connector, 1L, hash);
  }

  private void write(UUID connector, long tenant, String hash) {
    MappingTarget.Table spec = new MappingTarget.Table("connector", TABLE, true, MappingTarget.OnConflict.SKIP, List.of());
    Map<String, Object> sys = Map.of("_row_hash", hash, "_tenant", tenant, "_mapping", TABLE,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    runInTxAsSystem(utx, () -> writer.write(spec, List.of(Map.of("code", hash)), List.of(sys), tenant, connector));
  }

  private static String connector(UUID id, String name, String tags) {
    return connector(id, 1, name, tags);
  }

  private static String connector(UUID id, long tenant, String name, String tags) {
    return "INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, sync_mode, skip_policy) "
        + "VALUES ('" + id + "', " + tenant + ", '" + name + "', 'jdbc-generic', '{}'::jsonb, true, ARRAY[" + tags + "], "
        + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)";
  }

  private void sql(String s) {
    execSql(adminDs, s);
  }
}
