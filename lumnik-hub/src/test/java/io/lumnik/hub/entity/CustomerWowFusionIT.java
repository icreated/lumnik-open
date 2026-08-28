package io.lumnik.hub.entity;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The WOW end-to-end: two REAL legacy connector runs fused into one Customer, served over REST.
 *
 * <p>This is the fil that the unit/IT split never proved as one continuous flow:
 * <ol>
 *   <li>A fake Siebel (WireMock, SOAP QueryPage dialect) → a real {@code kind: soap} connector run
 *       lands {@code connector.cust_siebel_*} (tenant_id + RLS, born from {@code HybridJsonbWriter}).</li>
 *   <li>A flat-file export (temp CSV) → a real {@code csv-file} connector run lands
 *       {@code connector.t_cust_csv_*} (tenant_id + RLS, born from {@code DynamicTableWriter}).</li>
 *   <li>An {@code kind: Entity} manifest fuses both on {@code customer_code}.</li>
 *   <li>{@code GET /api/entities/customer_*} returns the fused entity: agreement, gap-fill,
 *       and a cross-source conflict with the {@code _conflicts} ledger.</li>
 * </ol>
 *
 * <p>RLS inheritance through the {@code security_invoker} view is proven separately by
 * {@code EntityFusionRlsIT}; here the new thing is that the sources arrive via genuine connector runs.
 */
@QuarkusTest
class CustomerWowFusionIT {

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject UserTransaction utx;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  static WireMockServer wm;

  static final RestAssuredConfig YAML_CFG = RestAssuredConfig.config().encoderConfig(
      EncoderConfig.encoderConfig().encodeContentTypeAs("application/yaml", ContentType.TEXT));

  // Unique per run: dodges the CSV dynamic_table registry AND the hybrid in-memory schemaCache.
  String suffix;
  String siebelTable;   // connector.<this>   (SOAP hybrid target)
  String csvTable;      // connector.t_<...>  (CSV dynamic table)
  String entityName;    // -> view connector.v_<this>

  Path csvFile;

  @BeforeAll
  static void startWireMock() {
    wm = new WireMockServer(0);
    wm.start();
    configureFor("localhost", wm.port());
  }

  @AfterAll
  static void stopWireMock() {
    if (wm != null) wm.stop();
  }

  @BeforeEach
  void setup() throws Exception {
    wm.resetAll();
    suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    siebelTable = "cust_siebel_" + suffix;
    csvTable = "t_cust_csv_" + suffix;
    entityName = "customer_" + suffix;

    // The flat-file export: C-100 agrees with Siebel on name; C-200 conflicts; C-300 is CSV-only.
    csvFile = Files.createTempFile("wow_cust_", ".csv");
    csvFile.toFile().deleteOnExit();
    Files.writeString(csvFile,
        "code,cname,email,ville\n"
        + "C-100,Jean Dupont,jd@x.fr,Paris\n"
        + "C-200,MARIE A.,m@x.fr,Lyon\n"
        + "C-300,Solo,solo@x.fr,Nice\n");

    // Fake Siebel: one QueryPage page, C-100 (agrees) + C-200 (conflicts: 'Marie' vs CSV 'MARIE A.').
    stubFor(post(urlPathEqualTo("/eai"))
        .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(envelope(
            "<Out><ListOfCustomer>"
            + "<Customer><CustCode>C-100</CustCode><Name>Jean Dupont</Name></Customer>"
            + "<Customer><CustCode>C-200</CustCode><Name>Marie</Name></Customer>"
            + "</ListOfCustomer><LastPage>true</LastPage></Out>"))));

    execSql(adminDs, "DROP VIEW IF EXISTS connector.\"v_" + entityName + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"" + siebelTable + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"" + csvTable + "\"");
  }

  @AfterEach
  void cleanup() {
    // Names are unique per run, so without this each run would leave a view, two tables, and
    // registry rows behind — accumulating schema objects across the suite. Mirror the @BeforeEach
    // drops and also clear the persisted registry rows (entity_manifest + the CSV dynamic_table).
    execSql(adminDs, "DROP VIEW IF EXISTS connector.\"v_" + entityName + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"" + siebelTable + "\"");
    execSql(adminDs, "DROP TABLE IF EXISTS connector.\"" + csvTable + "\"");
    execSql(adminDs, "DELETE FROM connector.entity_manifest WHERE name = '" + entityName + "'");
    execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE table_name = '" + csvTable + "'");
  }

  @Test
  void soap_and_csv_connector_runs_fuse_into_one_customer_served_over_rest() throws Exception {
    // 1. Run the fake-Siebel SOAP connector → connector.cust_siebel_*
    UUID siebelConn = seedSoapConnector();
    RunStatus soapRun = TenantContext.callAsSystem(() -> engine.runSync(siebelConn, new RunTrigger.Manual(1L)));
    assertThat(soapRun).as("SOAP run: %s", soapRun).isInstanceOf(RunStatus.Completed.class);

    // 2. Run the CSV connector → connector.t_cust_csv_*
    UUID csvConn = seedCsvConnector();
    RunStatus csvRun = TenantContext.callAsSystem(() -> engine.runSync(csvConn, new RunTrigger.Manual(1L)));
    assertThat(csvRun).as("CSV run: %s", csvRun).isInstanceOf(RunStatus.Completed.class);

    // 3. Fuse the two real tables on customer_code (Siebel first → representative for conflicts).
    String manifest = """
        kind: Entity
        name: %s
        match: [customer_code]
        sources:
          - table: %s
            map:
              customer_code: customer_code
              name: name
          - table: %s
            map:
              code: customer_code
              cname: name
              email: email
              ville: city
        """.formatted(entityName, siebelTable, csvTable);

    given().config(YAML_CFG).header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .contentType("application/yaml").body(manifest)
        .post("/api/entities/apply").then().statusCode(200);

    // 4a. C-100: name AGREES across both sources; email+city gap-filled from CSV; no conflict.
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .get("/api/entities/" + entityName + "?customer_code=C-100")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Jean Dupont"))
        .body("[0].email", equalTo("jd@x.fr"))
        .body("[0].city", equalTo("Paris"))
        .body("[0]._conflicts", equalTo("{}"));

    // 4b. C-200: name CONFLICTS — representative = Siebel ('Marie', listing order); ledger keeps both.
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .get("/api/entities/" + entityName + "?customer_code=C-200")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Marie"))
        .body("[0].email", equalTo("m@x.fr"))
        .body("[0].city", equalTo("Lyon"))
        .body("[0]._conflicts", containsString("Marie"))
        .body("[0]._conflicts", containsString("MARIE A."))
        .body("[0]._conflicts", containsString(siebelTable))
        .body("[0]._conflicts", containsString(csvTable));

    // 4c. C-300: present ONLY in the CSV export — still fused, no conflict.
    given().header("X-Tenant-ID", "1").header("X-User-ID", "1")
        .get("/api/entities/" + entityName + "?customer_code=C-300")
        .then().statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("Solo"))
        .body("[0].email", equalTo("solo@x.fr"))
        .body("[0].city", equalTo("Nice"))
        .body("[0]._conflicts", equalTo("{}"));
  }

  private UUID seedSoapConnector() {
    return runInTxAsSystem(utx, () -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("siebel-" + suffix);
      c.setTypeId("rest-generic");
      c.setConfig(Map.of("kind", "rest-generic", "base_url", "http://localhost:" + wm.port()));
      connectors.persist(c);
      connectors.flush();

      ConnectorEndpoint ep = new ConnectorEndpoint();
      ep.setTenantId(1L);
      ep.setConnectorId(c.getId());
      ep.setName("customers");
      ep.setMethod("POST");
      ep.setPath("/eai");
      ep.setEnabled(true);
      ep.setConfig(Map.of(
          "kind", "soap",
          "soap_action", "CustomerQueryPage",
          "body", "<In><PageSize>{{page_size}}</PageSize><StartRowNum>{{cursor}}</StartRowNum></In>",
          "pagination", Map.of("kind", "soap", "page_size", 100,
              "last_page_path", "$.Body.Out.LastPage",
              "response_path", "$.Body.Out.ListOfCustomer.Customer"),
          "incremental", Map.of("mode", "full"),
          "target", Map.of("kind", "hybrid", "table", "connector." + siebelTable,
              "unique_key", "$.CustCode",
              "promote", List.of(
                  Map.of("name", "customer_code", "path", "$.CustCode", "type", "text"),
                  Map.of("name", "name", "path", "$.Name", "type", "text")))));
      endpoints.persist(ep);
      endpoints.flush();
      return c.getId();
    });
  }

  private UUID seedCsvConnector() {
    String filePath = csvFile.toAbsolutePath().toString();
    return runInTxAsSystem(utx, () -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("csv-" + suffix);
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
      ep.setConfig(Map.of("name", "cust_csv_" + suffix));
      endpoints.persist(ep);
      endpoints.flush();
      return c.getId();
    });
  }

  private static String envelope(String inner) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
        + inner + "</soap:Body></soap:Envelope>";
  }
}
