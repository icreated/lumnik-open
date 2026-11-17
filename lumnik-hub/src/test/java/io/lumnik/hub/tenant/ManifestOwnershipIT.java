package io.lumnik.hub.tenant;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.app.AppRegistrationStore;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static io.lumnik.hub.testsupport.AppManifestYaml.manifest;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * #2050: an Entity, View or App manifest belongs to the tenant it was applied from, and only that tenant reads it.
 * Applied from the hub's own tenant (hubadmin), or written before ownership existed, it is shared: every tenant reads
 * it. Names stay unique on the hub; a name another tenant holds is refused without saying whose it is.
 */
@QuarkusTest
class ManifestOwnershipIT {

  private static final long OWNER = 1;
  private static final long OTHER = 20502;
  private static final String APP = "t2050-app";
  private static final String VIEW = "t2050-view";
  private static final String ENTITY = "t2050entity";
  private static final String ENTITY_YAML =
      "kind: Entity\nname: " + ENTITY + "\nscopes: [t2050]\nmatch: [code]\nsources:\n  - table: t_a\n    map: { code: code }\n";

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject EntityRegistry entities;
  @Inject AppRegistrationStore apps;
  @Inject EntitySchemaBuilder cards;

  @AfterEach
  void cleanUp() {
    Jdbc.update(adminDs, "test.cleanup", "DELETE FROM connector.app_registration WHERE name LIKE 't2050-%'", ps -> {});
    Jdbc.update(adminDs, "test.cleanup", "DELETE FROM connector.saved_view WHERE name LIKE 't2050-%'", ps -> {});
    Jdbc.update(adminDs, "test.cleanup", "DELETE FROM connector.entity_manifest WHERE name = ?", ps -> ps.setString(1, ENTITY));
    entities.remove(ENTITY);
  }

  /** An Entity: listed, read, put on the Ask card, applied over and deleted by its own tenant only. */
  @Test
  void an_entity_is_read_by_its_tenant_only() {
    Jdbc.update(adminDs, "test.seed", "INSERT INTO connector.entity_manifest (name, yaml_text, scopes, owner_tenant_id) "
        + "VALUES (?, ?, '{t2050}', ?)", ps -> {
          ps.setString(1, ENTITY);
          ps.setString(2, ENTITY_YAML);
          ps.setLong(3, OWNER);
        });
    assertThat(entities.findReloadingIfMissing(OWNER, ENTITY)).isNotNull();

    as(OWNER).get("/api/entities").then().statusCode(200).body("name", hasItem(ENTITY));
    as(OTHER).get("/api/entities").then().statusCode(200).body("name", not(hasItem(ENTITY)));
    as(OTHER).get("/api/entities/" + ENTITY).then().statusCode(404);
    assertThat(cards.resolve(OWNER, "t2050")).extracting(SchemaCard.Table::name).hasSize(1);
    assertThat(cards.resolve(OTHER, "t2050")).isEmpty();

    as(OTHER).body(ENTITY_YAML).post("/api/entities/apply").then().statusCode(409)
        .body("error", containsString("already taken"));
    as(OTHER).delete("/api/entities/" + ENTITY).then().statusCode(404);
    assertThat(owner("entity_manifest", ENTITY)).contains(OWNER);
  }

  @Test
  void an_app_is_read_by_the_tenant_it_was_applied_from_only() {
    as(OWNER).body(manifest(APP, "salon", "")).post("/api/apps/apply").then().statusCode(200);

    assertThat(owner("app_registration", APP)).contains(OWNER);
    as(OWNER).get("/api/apps").then().statusCode(200).body("name", hasItem(APP));
    as(OWNER).get("/api/apps/" + APP).then().statusCode(200);
    as(OTHER).get("/api/apps").then().statusCode(200).body("name", not(hasItem(APP)));
    as(OTHER).get("/api/apps/" + APP).then().statusCode(404);
  }

  @Test
  void an_app_applied_from_hubadmin_is_shared() {
    as(DeploymentTenancy.HUBADMIN_TENANT).body(manifest(APP, "salon", "")).post("/api/apps/apply").then().statusCode(200);

    assertThat(owner("app_registration", APP)).isEmpty();
    as(OTHER).get("/api/apps/" + APP).then().statusCode(200);
  }

  @Test
  void a_name_another_tenant_holds_is_refused_without_naming_it() {
    as(OWNER).body(manifest(APP, "salon", "")).post("/api/apps/apply").then().statusCode(200);

    as(OTHER).body(manifest(APP, "salon", "")).post("/api/apps/apply").then().statusCode(409)
        .body("error", containsString("already taken"));
    as(OTHER).delete("/api/apps/" + APP).then().statusCode(404);
    assertThat(owner("app_registration", APP)).contains(OWNER);
  }

  /** A tenant's own default hides the shared one for it alone; it can go, and its tenant falls back on the shared one. */
  @Test
  void a_tenant_default_is_its_own_and_falls_back_on_the_shared_one() {
    String shared = sharedDefault();
    as(OWNER).body(manifest(APP, "salon", "  default: true\n")).post("/api/apps/apply").then().statusCode(200);

    as(OWNER).get("/api/apps").then().statusCode(200)
        .body("findAll { it.default }.name", contains(APP));
    as(OTHER).get("/api/apps").then().statusCode(200)
        .body("findAll { it.default }.name", contains(shared));
    assertThat(sharedDefault()).as("the shared default is untouched").isEqualTo(shared);

    as(OWNER).delete("/api/apps/" + APP).then().statusCode(204);
    as(OWNER).get("/api/apps").then().statusCode(200)
        .body("findAll { it.default }.name", contains(shared));
  }

  /** A caller bound outside the scope of its tenant's own default still lands on the shared one. */
  @Test
  void a_bound_caller_who_cannot_see_its_tenants_default_reads_the_shared_one() {
    String shared = sharedDefault();
    String sharedScope = Jdbc.queryOne(adminDs, "test.scope", "SELECT scope FROM connector.app_registration WHERE name = ?",
        ps -> ps.setString(1, shared), rs -> rs.getString(1)).orElseThrow();
    as(OWNER).body(manifest(APP, "t2050ops", "  default: true\n")).post("/api/apps/apply").then().statusCode(200);

    assertThat(apps.list(OWNER, Set.of(sharedScope))).filteredOn(AppRegistrationStore.Row::isDefault)
        .extracting(AppRegistrationStore.Row::name).containsExactly(shared);
  }

  @Test
  void the_built_in_apps_stay_shared() {
    as(OTHER).get("/api/apps").then().statusCode(200).body("name", hasItem("clients"));
  }

  @Test
  void a_view_is_read_by_its_tenant_only() {
    Jdbc.update(adminDs, "test.seed", "INSERT INTO connector.saved_view (name, scope, title, yaml_text, owner_tenant_id) "
        + "VALUES (?, 'salon', 'Seeded', 'kind: View', ?)", ps -> {
          ps.setString(1, VIEW);
          ps.setLong(2, OWNER);
        });

    as(OWNER).get("/api/views").then().statusCode(200).body("name", hasItem(VIEW));
    as(OTHER).get("/api/views").then().statusCode(200).body("name", not(hasItem(VIEW)));
    as(OTHER).get("/api/views/" + VIEW).then().statusCode(404);
    as(OTHER).delete("/api/views/" + VIEW).then().statusCode(404);
  }

  private String sharedDefault() {
    return Jdbc.queryOne(adminDs, "test.default", "SELECT name FROM connector.app_registration "
        + "WHERE is_default AND owner_tenant_id IS NULL", ps -> {}, rs -> rs.getString(1)).orElseThrow();
  }

  private Optional<Long> owner(String table, String name) {
    return Jdbc.queryOne(adminDs, "test.owner", "SELECT owner_tenant_id FROM connector." + table + " WHERE name = ?",
        ps -> ps.setString(1, name), rs -> {
          long id = rs.getLong(1);
          return rs.wasNull() ? null : id;
        });
  }

  private static RequestSpecification as(long tenant) {
    return given().config(YamlBody.CONFIG)
        .header("X-Tenant-ID", String.valueOf(tenant)).header("X-User-ID", "1")
        .contentType("application/yaml");
  }
}
