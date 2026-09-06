package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.scalar;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The WOW take-down: a connector whose dynamic table feeds a fused entity cannot be deleted
 * out from under the view — that would be the anonymous 500 the audit found (Postgres refuses
 * {@code DROP TABLE} while {@code v_<entity>} depends on it). The delete is refused with a NAMED
 * 409 that points at the resolution, and {@code lm entity delete} is that resolution: remove the
 * entity, then the connector deletes cleanly.
 *
 * <p>A bare {@code @QuarkusTest}, deliberately (not {@code RlsEnforcedProfile}): the refusal query
 * ({@code dependentViews}), purge and the compiler all run on the admin (BYPASSRLS) datasource, so
 * the RLS posture is not what is under test here — the drop-ordering and the message are.
 */
@QuarkusTest
class EntityBlocksConnectorDeleteIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject EntityRegistry registry;
  @Inject UserTransaction utx;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config().encoderConfig(
      EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));

  String suffix;
  String csvName;     // endpoint target name -> physical table connector.t_<csvName>
  String csvTable;    // connector.t_<csvName>
  String entityName;  // -> view connector.v_<entityName>
  Path csvFile;

  @BeforeEach
  void setup() throws Exception {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    csvName = "bcd_" + suffix;
    csvTable = "t_" + csvName;
    entityName = "cust_" + suffix;

    csvFile = Files.createTempFile("bcd_", ".csv");
    csvFile.toFile().deleteOnExit();
    Files.writeString(csvFile, "code,cname\nC-1,Alice\nC-2,Bob\n");

    execSql(adminDs, "DROP VIEW IF EXISTS connector.\"v_" + entityName + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"" + csvTable + "\"");
  }

  @AfterEach
  void cleanup() {
    execSql(adminDs, "DROP VIEW IF EXISTS connector.\"v_" + entityName + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"" + csvTable + "\"");
    execSql(adminDs, "DELETE FROM connector.entity_manifest WHERE name = '" + entityName + "'");
    execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE table_name = '" + csvTable + "'");
  }

  @Test
  void connector_delete_is_refused_while_an_entity_reads_its_table_then_succeeds_after_entity_delete() {
    UUID csvConn = seedCsvConnector();
    RunStatus run = TenantContext.callAsSystem(() -> engine.runSync(csvConn, new RunTrigger.Manual(1L)));
    assertThat(run).as("CSV run: %s", run).isInstanceOf(RunStatus.Completed.class);
    assertThat(scalar(adminDs, "SELECT count(*) FROM information_schema.tables "
        + "WHERE table_schema='connector' AND table_name='" + csvTable + "'")).isEqualTo(1L);

    applyEntity();

    // The take-down, blocked: deleting the connector would drop the table the view depends on.
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + csvConn)
        .then().statusCode(409)
        .body("error", containsString(entityName))
        .body("error", containsString("lm entity delete"));

    // The connector and its table survive the refusal.
    assertThat(scalar(adminDs, "SELECT count(*) FROM connector.connector WHERE id='" + csvConn + "'"))
        .as("refused delete must leave the connector").isEqualTo(1L);
    assertThat(scalar(adminDs, "SELECT count(*) FROM information_schema.tables "
        + "WHERE table_schema='connector' AND table_name='" + csvTable + "'"))
        .as("refused delete must leave the table").isEqualTo(1L);

    // The resolution: remove the entity.
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/entities/" + entityName)
        .then().statusCode(204);
    assertThat(scalar(adminDs, "SELECT count(*) FROM connector.entity_manifest WHERE name='" + entityName + "'"))
        .as("entity delete must remove the manifest row").isEqualTo(0L);
    assertThat(scalar(adminDs, "SELECT count(*) FROM information_schema.views "
        + "WHERE table_schema='connector' AND table_name='v_" + entityName + "'"))
        .as("entity delete must drop the view").isEqualTo(0L);

    // Now the connector deletes cleanly.
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + csvConn)
        .then().statusCode(204);
    assertThat(scalar(adminDs, "SELECT count(*) FROM information_schema.tables "
        + "WHERE table_schema='connector' AND table_name='" + csvTable + "'"))
        .as("connector delete now drops the table").isEqualTo(0L);
  }

  @Test
  void a_foreign_view_over_the_table_is_refused_as_a_view_to_drop_not_a_dead_end_entity_gesture() {
    UUID csvConn = seedCsvConnector();
    TenantContext.callAsSystem(() -> engine.runSync(csvConn, new RunTrigger.Manual(1L)));
    // A DBA's own view over the connector's table — NOT an entity view, so no lm entity delete fixes it.
    String foreignView = "reporting_" + suffix;
    execSql(adminDs, "CREATE VIEW connector.\"" + foreignView + "\" AS SELECT * FROM connector.\"" + csvTable + "\"");
    try {
      given().headers(TestIdentity.tenantAndUser(1))
          .when().delete("/api/platform/connectors/" + csvConn)
          .then().statusCode(409)
          .body("error", containsString(foreignView))
          .body("error", containsString("drop it first"))
          .body("error", not(containsString("lm entity delete")));
    } finally {
      execSql(adminDs, "DROP VIEW IF EXISTS connector.\"" + foreignView + "\"");
    }
  }

  @Test
  void entity_delete_on_an_unknown_name_is_a_named_404() {
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/entities/no_such_entity_" + suffix)
        .then().statusCode(404)
        .body("error", containsString("no_such_entity_" + suffix));
  }

  @Test
  void a_materialized_view_over_the_table_also_blocks_the_delete() {
    UUID csvConn = seedCsvConnector();
    TenantContext.callAsSystem(() -> engine.runSync(csvConn, new RunTrigger.Manual(1L)));
    // A materialized view depends on the table just as a plain one does, and a DROP TABLE fails on
    // it too — the preflight must see relkind 'm', not only 'v'.
    String mv = "mv_" + suffix;
    execSql(adminDs, "CREATE MATERIALIZED VIEW connector.\"" + mv + "\" AS SELECT * FROM connector.\"" + csvTable + "\"");
    try {
      given().headers(TestIdentity.tenantAndUser(1))
          .when().delete("/api/platform/connectors/" + csvConn)
          .then().statusCode(409)
          .body("error", containsString(mv))
          .body("error", containsString("drop it first"));
    } finally {
      execSql(adminDs, "DROP MATERIALIZED VIEW IF EXISTS connector.\"" + mv + "\"");
    }
  }

  @Test
  void entity_delete_reloads_a_stale_registry_before_returning_404() {
    UUID csvConn = seedCsvConnector();
    TenantContext.callAsSystem(() -> engine.runSync(csvConn, new RunTrigger.Manual(1L)));
    applyEntity();
    // Simulate a second hub instance whose in-memory map never loaded this entity: the row + view
    // are in the DB, but the cache is cold. A naive delete would 404; the reload-on-miss must not.
    registry.remove(entityName);
    assertThat(registry.find(entityName)).as("cache is cold for this entity").isNull();

    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/entities/" + entityName)
        .then().statusCode(204);
    assertThat(scalar(adminDs, "SELECT count(*) FROM connector.entity_manifest WHERE name='" + entityName + "'"))
        .as("the stale cache reloaded and the real row was deleted").isEqualTo(0L);
  }

  @Test
  void connector_delete_names_the_entity_even_when_the_registry_cache_is_cold() {
    UUID csvConn = seedCsvConnector();
    TenantContext.callAsSystem(() -> engine.runSync(csvConn, new RunTrigger.Manual(1L)));
    applyEntity();
    // Cold cache on this "instance": the classification must still recognise connector.v_<name> as
    // ours (reload on miss), not fall back to the foreign-view "drop it first".
    registry.remove(entityName);
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + csvConn)
        .then().statusCode(409)
        .body("error", containsString("lm entity delete " + entityName))
        .body("error", not(containsString("drop it first")));
  }

  private void applyEntity() {
    String manifest = """
        kind: Entity
        name: %s
        match: [customer_code]
        sources:
          - table: %s
            map:
              code: customer_code
              cname: name
        """.formatted(entityName, csvTable);
    given().config(YAML_CFG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(manifest)
        .post("/api/entities/apply").then().statusCode(200);
  }

  private UUID seedCsvConnector() {
    String filePath = csvFile.toAbsolutePath().toString();
    return runInTxAsSystem(utx, () -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("bcd-" + suffix);
      c.setTypeId("csv-file");
      c.setConfig(Map.of(
          "kind", "csv-file",
          "transport", Map.of("kind", "local", "path", filePath),
          "parser", Map.of("has_header", true)));
      connectors.persist(c);
      connectors.flush();

      ConnectorEndpoint ep = new ConnectorEndpoint();
      ep.setTenantId(1L);
      ep.setConnectorId(c.getId());
      ep.setName("default");
      ep.setMethod("GET");
      ep.setPath(filePath);
      ep.setEnabled(true);
      ep.setConfig(Map.of("name", csvName));
      endpoints.persist(ep);
      endpoints.flush();
      return c.getId();
    });
  }
}
