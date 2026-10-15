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
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
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
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * #846: the connector behind a LOOKUP table is protected like one behind a fusion source. The
 * interlock asks Postgres' physical dependency graph, so no join-specific code exists — this pins
 * that the lookup is in that graph. Bare @QuarkusTest, like EntityBlocksConnectorDeleteIT.
 */
@QuarkusTest
class EntityJoinInterlockIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject UserTransaction utx;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  String suffix;
  String entityName;
  String prodName, catName;
  Path prodFile, catFile;

  @BeforeEach
  void setup() throws Exception {
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    entityName = "ijoin_" + suffix;
    prodName = "ijp_" + suffix;
    catName = "ijc_" + suffix;
    prodFile = Files.createTempFile("ijp_", ".csv");
    catFile = Files.createTempFile("ijc_", ".csv");
    prodFile.toFile().deleteOnExit();
    catFile.toFile().deleteOnExit();
    Files.writeString(prodFile, "pid,cid,pname\nP1,C1,Pump\n");
    Files.writeString(catFile, "cid,cname\nC1,Pumps\n");
  }

  @AfterEach
  void cleanup() {
    execSql(adminDs, "DROP VIEW IF EXISTS connector.\"v_" + entityName + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"t_" + prodName + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"t_" + catName + "\"");
    execSql(adminDs, "DELETE FROM connector.entity_manifest WHERE name = '" + entityName + "'");
    execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE table_name IN ('t_" + prodName + "', 't_" + catName + "')");
  }

  @Test
  void the_connector_behind_a_lookup_cannot_be_deleted_while_an_entity_joins_it() {
    UUID prod = seedCsvConnector("ijp-" + suffix, prodFile, prodName);
    UUID cat = seedCsvConnector("ijc-" + suffix, catFile, catName);
    for (UUID id : new UUID[]{prod, cat}) {
      RunStatus run = TenantContext.callAsSystem(() -> engine.runSync(id, new RunTrigger.Manual(1L)));
      assertThat(run).as("CSV run: %s", run).isInstanceOf(RunStatus.Completed.class);
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body("""
            kind: Entity
            name: %s
            match: [product_id]
            sources:
              - table: t_%s
                map: { pid: product_id, cid: category_id }
            joins:
              - table: t_%s
                from: category_id
                to: cid
                map: { cname: category_name }
            """.formatted(entityName, prodName, catName))
        .post("/api/entities/apply").then().statusCode(200);

    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + cat)
        .then().statusCode(409)
        .body("error", containsString(entityName))
        .body("error", containsString("lm entity delete"));

    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/entities/" + entityName).then().statusCode(204);
    given().headers(TestIdentity.tenantAndUser(1))
        .when().delete("/api/platform/connectors/" + cat).then().statusCode(204);
  }

  private UUID seedCsvConnector(String name, Path file, String target) {
    String filePath = file.toAbsolutePath().toString();
    return runInTxAsSystem(utx, () -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName(name);
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
      ep.setConfig(Map.of("name", target));
      endpoints.persist(ep);
      endpoints.flush();
      return c.getId();
    });
  }
}
